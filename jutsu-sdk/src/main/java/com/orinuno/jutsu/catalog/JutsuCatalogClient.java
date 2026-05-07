package com.orinuno.jutsu.catalog;

import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reactive client for jut.su's catalog AJAX endpoint.
 *
 * <p>Issues {@code POST /anime/{path}/} with form body
 *
 * <pre>{@code ajax_load=yes&start_from_page={page}&show_search={query}&anime_of_user=}</pre>
 *
 * The website always returns the partial HTML for that filter+page+query combo (≤ 30 cards), with a
 * {@code var anime_page_next} marker telling us whether to fetch the next page.
 *
 * <p>This client owns its own {@link WebClient} (built once in the constructor) and shares the
 * {@link JutsuRateLimiter} and {@link JutsuDriftDetector} with the rest of the SDK so RPS budgeting
 * + drift events stay coherent across endpoints.
 *
 * <p><strong>Catalog calls intentionally fly anonymous</strong> — the {@link
 * com.orinuno.jutsu.auth.JutsuSessionManager} is deliberately NOT injected here. jut.su is built on
 * DataLife Engine, which persists the user's last clicked sort order on the account and serves
 * <em>that</em> ordering on every subsequent request bearing the user's cookies — even when the URL
 * has no sort segment ({@code POST /anime/}). Attaching the SDK's logged-in session to catalog
 * calls would therefore make the response order depend on whatever a human (or an earlier
 * automation run) last clicked under the same account, breaking the SDK's "default sort =
 * by-rating" contract for everyone sharing those credentials. Episode decode and premium-meta calls
 * still go through the session manager — they NEED auth — but listing endpoints stay anonymous by
 * construction. This invariant is statically enforced: the constructor doesn't accept a session
 * manager, so a future refactor cannot accidentally re-introduce the leak. (Cloudflare-friendliness
 * was the original justification for sending cookies here; in practice anonymous calls are not
 * throttled harder, and the personalised-sort regression observed in production outweighs the
 * hypothetical benefit.)
 */
@Slf4j
public final class JutsuCatalogClient {

    /** Documented page size on jut.su (current, may change with redesigns). */
    public static final int PAGE_SIZE = 30;

    /** Lower bound on a non-empty AJAX response body — anything smaller is suspicious. */
    static final int MIN_NON_EMPTY_BODY_BYTES = 200;

    private final WebClient client;
    private final JutsuRateLimiter rateLimiter;
    private final JutsuDriftDetector driftDetector;

    public JutsuCatalogClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuDriftDetector driftDetector,
            WebClient.Builder webClientBuilder) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (rateLimiter == null) throw new IllegalArgumentException("rateLimiter must not be null");
        if (driftDetector == null) {
            throw new IllegalArgumentException("driftDetector must not be null");
        }
        if (webClientBuilder == null) {
            throw new IllegalArgumentException("webClientBuilder must not be null");
        }
        this.client =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                        .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8")
                        .defaultHeader(HttpHeaders.ORIGIN, "https://jut.su")
                        .defaultHeader(HttpHeaders.REFERER, "https://jut.su/anime/")
                        .defaultHeader("X-Requested-With", "XMLHttpRequest")
                        .build();
        this.rateLimiter = rateLimiter;
        this.driftDetector = driftDetector;
    }

    /** Fetch one catalog page. Always anonymous — see class doc for the rationale. */
    public Mono<JutsuCatalogPage> browse(JutsuCatalogRequest request) {
        if (request == null) {
            return Mono.error(new IllegalArgumentException("request must not be null"));
        }
        return rateLimiter
                .acquire()
                .then(performPost(request))
                .map(html -> parsePage(html, request));
    }

    private Mono<String> performPost(JutsuCatalogRequest request) {
        String absolutePath = "https://jut.su" + request.resolvePath();
        String body = composeFormBody(request);
        String parserSource = "JutsuCatalogClient";
        JutsuParserContext httpCtx = JutsuParserContext.lenient(driftDetector, parserSource);
        return client.post()
                .uri(absolutePath)
                .headers(h -> h.setContentType(MediaType.APPLICATION_FORM_URLENCODED))
                .body(BodyInserters.fromValue(body))
                .exchangeToMono(
                        resp -> {
                            int status = resp.statusCode().value();
                            if (status != 200) {
                                httpCtx.observe(
                                        JutsuDriftSignal.UNEXPECTED_HTTP_STATUS,
                                        "POST " + absolutePath + " returned " + status);
                            }
                            MediaType contentType = resp.headers().contentType().orElse(null);
                            return resp.bodyToMono(byte[].class)
                                    .defaultIfEmpty(new byte[0])
                                    .map(
                                            bytes -> {
                                                if (bytes.length == 0) {
                                                    httpCtx.observe(
                                                            JutsuDriftSignal.EMPTY_RESPONSE,
                                                            "POST "
                                                                    + absolutePath
                                                                    + " body is empty");
                                                } else if (bytes.length
                                                        < MIN_NON_EMPTY_BODY_BYTES) {
                                                    httpCtx.observe(
                                                            JutsuDriftSignal.RESPONSE_TOO_SMALL,
                                                            "POST "
                                                                    + absolutePath
                                                                    + " body is "
                                                                    + bytes.length
                                                                    + " bytes");
                                                }
                                                return JutsuHtmlCharset.decode(bytes, contentType);
                                            });
                        });
    }

    private JutsuCatalogPage parsePage(String html, JutsuCatalogRequest request) {
        JutsuParserContext ctx = JutsuParserContext.lenient(driftDetector, "JutsuCatalogParser");
        return new JutsuCatalogParser(ctx).parse(html, request.page());
    }

    static String composeFormBody(JutsuCatalogRequest request) {
        String search = request.hasSearch() ? request.searchQuery() : "";
        // x-www-form-urlencoded with windows-1251 is what the website's JS posts; the body is
        // ASCII-safe except for the show_search query, so we URL-encode that with UTF-8 — jut.su
        // returns identical results for either encoding because the search query is matched on
        // the multi-byte index.
        return "ajax_load=yes&start_from_page="
                + request.page()
                + "&show_search="
                + URLEncoder.encode(search, StandardCharsets.UTF_8)
                + "&anime_of_user=";
    }
}
