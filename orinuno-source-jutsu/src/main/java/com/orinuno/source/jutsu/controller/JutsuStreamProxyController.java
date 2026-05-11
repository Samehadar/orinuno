package com.orinuno.source.jutsu.controller;

import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * PROXY-1 — Backend pass-through proxy for jut.su CDN URLs.
 *
 * <p><strong>Why this exists</strong>: the {@code r{N}.yandexwebcache.org/...} URLs that {@link
 * com.orinuno.jutsu.JutsuClient} extracts are signed against the session that fetched the episode
 * page. Backend gets one URL, the browser gets a different (also authenticated) URL — Yandex CDN
 * rejects cross-session URLs with HTTP 403 instantly. See {@code docs/quirks-and-hacks.md} → "JutSu
 * DLE auth + sticky cookies + 1 RPS hard cap" → "CDN URLs are session-bound".
 *
 * <p>This controller bridges that gap: the browser hits {@code GET /api/v1/sources/jutsu/stream
 * ?url={cdnUrl}}, we re-issue the request from inside backend's session, and stream the response
 * straight back without buffering. The browser's {@code <video>} tag never talks to Yandex
 * directly. The legacy alias {@code /api/v1/providers/jutsu/stream} routes through the same handler
 * so older frontends keep working during the deprecation window.
 *
 * <p><strong>Hardening</strong>:
 *
 * <ul>
 *   <li><strong>Host whitelist</strong>: only {@code *.yandexwebcache.org} is proxied. This
 *       endpoint is NOT an open relay.
 *   <li><strong>Rate limit shared with the decoder</strong>: every proxied request consumes a token
 *       from the same {@link JutsuRateLimiter} bucket as {@link com.orinuno.jutsu.JutsuClient} so a
 *       chatty browser cannot evict the decoder's RPS budget.
 *   <li><strong>Range forwarded verbatim</strong>: the browser asks for {@code bytes=…} (HTML5
 *       seek), we pass it upstream, we pass {@code Content-Range} / {@code Content-Length} / {@code
 *       Accept-Ranges} back. Without this, Safari refuses to seek and Chrome buffers the whole file
 *       before allowing playback.
 *   <li><strong>Streaming, not buffering</strong>: body is piped as {@code Flux<DataBuffer>} via
 *       {@link org.springframework.web.server.ServerHttpResponse} so a 700 MB episode does not need
 *       700 MB of heap. WebClient's default 256 KB in-memory codec limit does not apply to {@code
 *       bodyToFlux(DataBuffer.class)}.
 * </ul>
 *
 * <p><strong>Out of scope for this iteration</strong>:
 *
 * <ul>
 *   <li>HEAD requests (browsers issue them sometimes; for now we just answer with the GET path and
 *       let HTTP machinery throw away the body).
 *   <li>Per-IP throttling on the proxy itself — we rely on the inbound API-key gate.
 *   <li>Caching the upstream response on disk; that's PROXY-2 territory and changes the storage
 *       budget.
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "Sources", description = "JutSu CDN pass-through proxy (PROXY-1)")
public class JutsuStreamProxyController {

    /** Canonical path under the per-source API tier. */
    static final String CANONICAL_PATH = "/api/v1/sources/jutsu/stream";

    /**
     * Legacy path kept as a deprecation alias. Browsers and frontends were originally pointed at
     * {@code /api/v1/providers/jutsu/stream}; we cannot break them in-flight, so both URLs route
     * through the same handler. Removal target: see ADR 0001 follow-up — at least one minor release
     * after the new path ships.
     */
    static final String LEGACY_PATH = "/api/v1/providers/jutsu/stream";

    /** Suffix-match whitelist for upstream hosts we will proxy. */
    static final List<String> ALLOWED_HOST_SUFFIXES = List.of(".yandexwebcache.org");

    /**
     * Headers we forward from the upstream response to the browser. Whitelist (not blacklist) so
     * that a future Yandex header change can't accidentally leak something we shouldn't pass on
     * (e.g. a session cookie).
     */
    private static final Set<String> FORWARDED_RESPONSE_HEADERS =
            Set.of(
                    HttpHeaders.CONTENT_TYPE.toLowerCase(),
                    HttpHeaders.CONTENT_LENGTH.toLowerCase(),
                    HttpHeaders.CONTENT_RANGE.toLowerCase(),
                    HttpHeaders.ACCEPT_RANGES.toLowerCase(),
                    HttpHeaders.CACHE_CONTROL.toLowerCase(),
                    HttpHeaders.LAST_MODIFIED.toLowerCase(),
                    HttpHeaders.ETAG.toLowerCase());

    private final JutsuRateLimiter rateLimiter;
    private final JutsuSessionManager sessionManager;
    private final WebClient streamClient;
    private final MeterRegistry meterRegistry;
    private final Counter forwardedRequests;
    private final Counter rejectedRequests;
    private final ConcurrentMap<Integer, Counter> upstreamStatusCounters =
            new ConcurrentHashMap<>();

    /** Stable desktop UA — kept inline so this service stays Kodik-free at the dep level. */
    private static final String STABLE_DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    public JutsuStreamProxyController(
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {
        this.rateLimiter = rateLimiter;
        this.sessionManager = sessionManager;
        this.meterRegistry = meterRegistry;
        // Dedicated WebClient. We deliberately do NOT raise the in-memory codec limit because
        // {@code bodyToFlux(DataBuffer.class)} streams chunk-by-chunk and never needs the limit.
        this.streamClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, STABLE_DESKTOP_UA)
                        .defaultHeader(HttpHeaders.REFERER, "https://jut.su/")
                        .defaultHeader(HttpHeaders.ACCEPT, "*/*")
                        .build();
        this.forwardedRequests =
                Counter.builder("orinuno.providers.jutsu.proxy.forwarded.total")
                        .description("Pass-through requests that reached upstream")
                        .register(meterRegistry);
        this.rejectedRequests =
                Counter.builder("orinuno.providers.jutsu.proxy.rejected.total")
                        .description("Pass-through requests rejected by the host whitelist")
                        .register(meterRegistry);
    }

    @GetMapping(value = CANONICAL_PATH, produces = MediaType.ALL_VALUE)
    @Operation(
            summary = "Stream a jut.su CDN URL through backend's session",
            description =
                    "Re-issues the GET against the upstream URL using backend's authenticated"
                        + " jut.su session and streams the response back to the browser without"
                        + " buffering. Required because Yandex CDN signs URLs against the"
                        + " originating session — a URL fetched by backend cannot be opened"
                        + " directly by the browser. Whitelisted to *.yandexwebcache.org. Pass"
                        + " ?filename=... to receive a Content-Disposition: attachment header so"
                        + " the browser triggers a download with the supplied filename instead of"
                        + " inline playback.")
    public Mono<Void> stream(
            @RequestParam("url") String upstreamUrl,
            @RequestParam(value = "filename", required = false) @Nullable String filename,
            ServerWebExchange exchange) {
        return doStream(upstreamUrl, filename, exchange);
    }

    @GetMapping(value = LEGACY_PATH, produces = MediaType.ALL_VALUE)
    @Operation(
            deprecated = true,
            summary = "[Deprecated] use GET /api/v1/sources/jutsu/stream",
            description =
                    "Legacy alias for the canonical /api/v1/sources/jutsu/stream. Same handler,"
                            + " same semantics, kept only so existing frontends keep working during"
                            + " the transition. Remove after at least one minor release.")
    @Deprecated
    public Mono<Void> streamLegacy(
            @RequestParam("url") String upstreamUrl,
            @RequestParam(value = "filename", required = false) @Nullable String filename,
            ServerWebExchange exchange) {
        return doStream(upstreamUrl, filename, exchange);
    }

    private Mono<Void> doStream(
            String upstreamUrl, @Nullable String filename, ServerWebExchange exchange) {
        URI uri;
        try {
            uri = URI.create(upstreamUrl);
        } catch (IllegalArgumentException ex) {
            return reject(exchange, HttpStatus.BAD_REQUEST, "invalid url");
        }
        if (!isAllowedHost(uri)) {
            rejectedRequests.increment();
            log.warn("🚫 JutSu proxy rejected non-whitelisted host: {}", sanitize(uri.getHost()));
            return reject(exchange, HttpStatus.FORBIDDEN, "host not whitelisted");
        }
        String range = exchange.getRequest().getHeaders().getFirst(HttpHeaders.RANGE);
        return rateLimiter
                .acquire()
                .then(sessionManager.cookieHeader().defaultIfEmpty(""))
                .flatMap(
                        cookies ->
                                streamClient
                                        .get()
                                        .uri(uri)
                                        .headers(
                                                h -> {
                                                    if (!cookies.isEmpty()) {
                                                        h.add(HttpHeaders.COOKIE, cookies);
                                                    }
                                                    if (range != null && !range.isBlank()) {
                                                        h.add(HttpHeaders.RANGE, range);
                                                    }
                                                })
                                        .exchangeToMono(
                                                upstream -> {
                                                    forwardedRequests.increment();
                                                    upstreamStatusCounter(
                                                                    upstream.statusCode().value())
                                                            .increment();
                                                    var responseHeaders =
                                                            exchange.getResponse().getHeaders();
                                                    upstream.headers()
                                                            .asHttpHeaders()
                                                            .forEach(
                                                                    (name, values) -> {
                                                                        if (FORWARDED_RESPONSE_HEADERS
                                                                                .contains(
                                                                                        name
                                                                                                .toLowerCase())) {
                                                                            responseHeaders.put(
                                                                                    name, values);
                                                                        }
                                                                    });
                                                    if (filename != null && !filename.isBlank()) {
                                                        responseHeaders.set(
                                                                HttpHeaders.CONTENT_DISPOSITION,
                                                                contentDisposition(filename));
                                                    }
                                                    exchange.getResponse()
                                                            .setStatusCode(upstream.statusCode());
                                                    Flux<DataBuffer> body =
                                                            upstream.bodyToFlux(DataBuffer.class);
                                                    return exchange.getResponse().writeWith(body);
                                                }));
    }

    static boolean isAllowedHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) return false;
        String lower = host.toLowerCase();
        for (String suffix : ALLOWED_HOST_SUFFIXES) {
            if (lower.endsWith(suffix)) return true;
        }
        return false;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String reason) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.TEXT_PLAIN);
        var buffer = exchange.getResponse().bufferFactory().wrap(reason.getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Counter upstreamStatusCounter(int status) {
        return upstreamStatusCounters.computeIfAbsent(
                status,
                s ->
                        Counter.builder("orinuno.providers.jutsu.proxy.upstream_status.total")
                                .description("Upstream response status seen by the proxy")
                                .tags(Tags.of("status", String.valueOf(s)))
                                .register(meterRegistry));
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        return s.replace('\n', '_').replace('\r', '_');
    }

    /**
     * Build a safe {@code Content-Disposition: attachment; filename="..."; filename*=UTF-8''...}
     * value for the user-supplied filename. We:
     *
     * <ul>
     *   <li>Strip control characters and CRLF to prevent header-injection (a malicious frontend
     *       calling our proxy could otherwise smuggle arbitrary headers).
     *   <li>Replace path separators ({@code /} {@code \}) with underscores so the filename can't
     *       direct the browser to write outside the user's chosen download directory.
     *   <li>Quote the ASCII-fallback {@code filename="..."} value with backslash escapes for quotes
     *       and backslashes (RFC 6266 § 4.1).
     *   <li>Add an {@code filename*=UTF-8''...} variant percent-encoded per RFC 5987 so that
     *       Cyrillic / unicode names render correctly in modern browsers (Chrome, Firefox, Safari)
     *       while old browsers fall back to the ASCII version above.
     * </ul>
     *
     * <p>Cap length at 200 chars — Windows/macOS filename limits are well above that, but some
     * download UIs truncate longer values badly.
     */
    static String contentDisposition(String rawFilename) {
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < rawFilename.length() && ascii.length() < 200; i++) {
            char c = rawFilename.charAt(i);
            if (c < 0x20 || c == 0x7f) continue; // strip control chars
            if (c == '/' || c == '\\') {
                ascii.append('_');
                continue;
            }
            if (c == '"') {
                ascii.append('\\').append('"');
                continue;
            }
            if (c < 0x80) {
                ascii.append(c);
            } else {
                ascii.append('_'); // ASCII-only fallback gets transliterated for non-Latin chars
            }
        }
        if (ascii.length() == 0) ascii.append("download");
        String utf8 =
                java.net.URLEncoder.encode(rawFilename, java.nio.charset.StandardCharsets.UTF_8)
                        .replace("+", "%20");
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8;
    }
}
