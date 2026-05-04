package com.orinuno.jutsu.episode;

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
 * Reactive client that fetches a jut.su episode page and returns lightweight metadata, without
 * triggering the heavier video-decode pipeline.
 *
 * <p>Use this when you need page chrome (title, thumbnail, navigation, paywall flag) for catalogue
 * UIs and don't want to spend the time/CPU on player JS extraction. For actual video URL
 * extraction, use {@code JutsuDecoder}.
 */
@Slf4j
public final class JutsuEpisodeMetaClient {

    /** Episode pages are large (60KB+); below this is suspicious. */
    static final int MIN_BODY_BYTES = 5_000;

    private final WebClient client;
    private final JutsuRateLimiter rateLimiter;
    private final JutsuSessionManager sessionManager;
    private final JutsuDriftDetector driftDetector;

    public JutsuEpisodeMetaClient(
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
        this.client =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, config.userAgent())
                        .defaultHeader(
                                HttpHeaders.ACCEPT,
                                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ru-RU,ru;q=0.9,en;q=0.8")
                        .defaultHeader(HttpHeaders.REFERER, "https://jut.su/")
                        .build();
        this.rateLimiter = rateLimiter;
        this.sessionManager = sessionManager;
        this.driftDetector = driftDetector;
    }

    /**
     * Fetch and parse the metadata for a single episode page.
     *
     * @param relativeOrAbsoluteUrl either a relative path ({@code
     *     /onepuunchman/season-1/episode-1.html}) or a fully-qualified URL ({@code
     *     https://jut.su/onepuunchman/episode-1.html})
     */
    public Mono<JutsuEpisodeMeta> getMeta(String relativeOrAbsoluteUrl) {
        if (relativeOrAbsoluteUrl == null || relativeOrAbsoluteUrl.isBlank()) {
            return Mono.error(new IllegalArgumentException("url must not be blank"));
        }
        String absoluteUrl = toAbsolute(relativeOrAbsoluteUrl);
        String relative = toRelative(absoluteUrl);
        return rateLimiter
                .acquire()
                .then(sessionManager.cookieHeader().defaultIfEmpty(""))
                .flatMap(cookieHeader -> performGet(absoluteUrl, cookieHeader))
                .map(html -> parse(html, relative));
    }

    private Mono<String> performGet(String url, String cookieHeader) {
        JutsuParserContext httpCtx =
                JutsuParserContext.lenient(driftDetector, "JutsuEpisodeMetaClient");
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

    private JutsuEpisodeMeta parse(String html, String expectedRelativeUrl) {
        JutsuParserContext ctx =
                JutsuParserContext.lenient(driftDetector, "JutsuEpisodePageParser");
        JutsuEpisodeMeta meta = new JutsuEpisodePageParser(ctx).parse(html, expectedRelativeUrl);
        if (meta == null) {
            throw new IllegalStateException(
                    "episode page parse returned null for " + expectedRelativeUrl);
        }
        return meta;
    }

    static String toAbsolute(String urlOrPath) {
        String trimmed = urlOrPath.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed;
        if (trimmed.startsWith("/")) return "https://jut.su" + trimmed;
        return "https://jut.su/" + trimmed;
    }

    static String toRelative(String absoluteUrl) {
        // Strip scheme + host so JutsuEpisodePageParser sees the same shape it gets from anchor
        // hrefs (which are always relative on jut.su).
        int idx = absoluteUrl.indexOf("/", "https://".length());
        if (idx < 0) return absoluteUrl;
        return absoluteUrl.substring(idx);
    }
}
