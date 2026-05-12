package com.orinuno.jutsu.info;

import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuParserContext;
import com.orinuno.jutsu.parser.JutsuHtmlCharset;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reactive client for jut.su's anime info page ({@code GET /{slug}/}). One call returns the full
 * anime page HTML; the parser runs offline.
 *
 * <p>Like the other SDK clients, owns a private {@link WebClient} but shares the rate limiter,
 * session manager and drift detector with the rest of the SDK.
 */
@Slf4j
public final class JutsuAnimeInfoClient {

    /** Lower bound on a non-empty info page — anything smaller is suspicious. */
    static final int MIN_BODY_BYTES = 5_000;

    private final WebClient client;
    private final JutsuRateLimiter rateLimiter;
    private final JutsuSessionManager sessionManager;
    private final JutsuDriftDetector driftDetector;
    private final String baseUrl;

    public JutsuAnimeInfoClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            JutsuDriftDetector driftDetector,
            WebClient.Builder webClientBuilder) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (rateLimiter == null) throw new IllegalArgumentException("rateLimiter must not be null");
        if (sessionManager == null) {
            throw new IllegalArgumentException("sessionManager must not be null");
        }
        if (driftDetector == null) {
            throw new IllegalArgumentException("driftDetector must not be null");
        }
        if (webClientBuilder == null) {
            throw new IllegalArgumentException("webClientBuilder must not be null");
        }
        this.baseUrl =
                config.baseUrl() != null && config.baseUrl().endsWith("/")
                        ? config.baseUrl().substring(0, config.baseUrl().length() - 1)
                        : config.baseUrl();
        this.client =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                        .defaultHeader(
                                HttpHeaders.ACCEPT,
                                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8")
                        .defaultHeader(HttpHeaders.REFERER, baseUrl + "/")
                        .build();
        this.rateLimiter = rateLimiter;
        this.sessionManager = sessionManager;
        this.driftDetector = driftDetector;
    }

    /** Fetch and parse the anime info page for {@code slug}. */
    public Mono<JutsuAnimeInfo> getInfo(String slug) {
        if (slug == null || slug.isBlank()) {
            return Mono.error(new IllegalArgumentException("slug must not be blank"));
        }
        String url = baseUrl + "/" + slug + "/";
        return rateLimiter
                .acquire()
                .then(sessionManager.cookieHeader().defaultIfEmpty(""))
                .flatMap(cookieHeader -> performGet(url, cookieHeader))
                .map(html -> parse(html, slug));
    }

    private Mono<String> performGet(String url, String cookieHeader) {
        JutsuParserContext httpCtx =
                JutsuParserContext.lenient(driftDetector, "JutsuAnimeInfoClient");
        return client.get()
                .uri(url)
                .headers(
                        h -> {
                            if (!cookieHeader.isEmpty()) {
                                h.add(HttpHeaders.COOKIE, cookieHeader);
                            }
                        })
                .exchangeToMono(
                        resp -> {
                            int status = resp.statusCode().value();
                            if (status != 200) {
                                httpCtx.observe(
                                        JutsuDriftSignal.UNEXPECTED_HTTP_STATUS,
                                        "GET " + url + " returned " + status);
                            }
                            MediaType ct = resp.headers().contentType().orElse(null);
                            return resp.bodyToMono(byte[].class)
                                    .defaultIfEmpty(new byte[0])
                                    .map(
                                            bytes -> {
                                                if (bytes.length == 0) {
                                                    httpCtx.observe(
                                                            JutsuDriftSignal.EMPTY_RESPONSE,
                                                            "GET " + url + " body is empty");
                                                } else if (bytes.length < MIN_BODY_BYTES) {
                                                    httpCtx.observe(
                                                            JutsuDriftSignal.RESPONSE_TOO_SMALL,
                                                            "GET "
                                                                    + url
                                                                    + " body is "
                                                                    + bytes.length
                                                                    + " bytes");
                                                }
                                                return JutsuHtmlCharset.decode(bytes, ct);
                                            });
                        });
    }

    private JutsuAnimeInfo parse(String html, String slug) {
        JutsuParserContext ctx = JutsuParserContext.lenient(driftDetector, "JutsuAnimeInfoParser");
        JutsuAnimeInfo info = new JutsuAnimeInfoParser(ctx).parse(html, slug);
        if (info == null) {
            // Parser already observed the drift event; we surface a typed null to the Mono so
            // callers can log/skip without a special exception.
            throw new IllegalStateException("anime info parse returned null for slug=" + slug);
        }
        return info;
    }
}
