package com.orinuno.controller;

import com.orinuno.client.http.RotatingUserAgentProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * Pass-through proxy for jut.su poster thumbnails (ADR 0016 P1a follow-up).
 *
 * <p><strong>Why this exists</strong>: jut.su's catalog cards point at {@code
 * https://gen.jut.su/uploads/animethumbs/anime_*.jpg}. Some browsers / CDN regions reject those
 * URLs when the {@code <img>} tag opens them cross-origin from {@code localhost} or other origins —
 * empty referer + missing TLS-fingerprint hits a Cloudflare / origin-policy block. The result is
 * the demo UI showing card chrome but no posters. A backend-mediated fetch with the right headers
 * (Referer + UA) sidesteps the entire class of problem.
 *
 * <p>The proxy is intentionally tiny: same-origin URL → backend → upstream → bytes back. We do NOT
 * cache on disk in this iteration; CDN and HTTP layer caching is enough for thumbnails.
 *
 * <p><strong>Hardening</strong>:
 *
 * <ul>
 *   <li><strong>Host whitelist</strong>: only {@code gen.jut.su} and {@code static.jut.su} are
 *       proxied. Anything else returns HTTP 403 — this endpoint is not an open relay.
 *   <li><strong>Streaming</strong>: bytes flow as {@code Flux<DataBuffer>} so a misconfigured
 *       upstream can't blow heap.
 *   <li><strong>Header whitelist</strong>: only image-relevant headers are forwarded back.
 * </ul>
 */
@Slf4j
@RestController
@Tag(name = "Sources", description = "JutSu poster thumbnail proxy")
public class JutsuPosterProxyController {

    static final String CANONICAL_PATH = "/api/v1/sources/jutsu/poster";

    static final List<String> ALLOWED_HOSTS = List.of("gen.jut.su", "static.jut.su", "jut.su");

    private static final Set<String> FORWARDED_RESPONSE_HEADERS =
            Set.of(
                    HttpHeaders.CONTENT_TYPE.toLowerCase(),
                    HttpHeaders.CONTENT_LENGTH.toLowerCase(),
                    HttpHeaders.CACHE_CONTROL.toLowerCase(),
                    HttpHeaders.LAST_MODIFIED.toLowerCase(),
                    HttpHeaders.ETAG.toLowerCase());

    private final WebClient posterClient;
    private final MeterRegistry meterRegistry;
    private final Counter forwardedRequests;
    private final Counter rejectedRequests;
    private final ConcurrentMap<Integer, Counter> upstreamStatusCounters =
            new ConcurrentHashMap<>();

    public JutsuPosterProxyController(
            RotatingUserAgentProvider userAgents,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.posterClient =
                webClientBuilder
                        .defaultHeader(HttpHeaders.USER_AGENT, userAgents.stableDesktop())
                        .defaultHeader(HttpHeaders.REFERER, "https://jut.su/")
                        .defaultHeader(HttpHeaders.ACCEPT, "image/*,*/*;q=0.8")
                        .build();
        this.forwardedRequests =
                Counter.builder("orinuno.providers.jutsu.poster_proxy.forwarded.total")
                        .description("Poster proxy requests that reached upstream")
                        .register(meterRegistry);
        this.rejectedRequests =
                Counter.builder("orinuno.providers.jutsu.poster_proxy.rejected.total")
                        .description("Poster proxy requests rejected by the host whitelist")
                        .register(meterRegistry);
    }

    @GetMapping(value = CANONICAL_PATH, produces = MediaType.ALL_VALUE)
    @Operation(
            summary = "Stream a jut.su poster thumbnail through backend",
            description =
                    "Re-issues the GET against the upstream poster URL with the right Referer / UA"
                            + " so the browser doesn't have to talk to gen.jut.su / static.jut.su"
                            + " cross-origin. Whitelisted to gen.jut.su / static.jut.su / jut.su"
                            + " hostnames; anything else returns 403.")
    public Mono<Void> proxy(@RequestParam("url") String upstreamUrl, ServerWebExchange exchange) {
        URI uri;
        try {
            uri = URI.create(upstreamUrl);
        } catch (IllegalArgumentException ex) {
            return reject(exchange, HttpStatus.BAD_REQUEST, "invalid url");
        }
        if (!isAllowedHost(uri)) {
            rejectedRequests.increment();
            log.warn(
                    "🚫 JutSu poster proxy rejected non-whitelisted host: {}",
                    sanitize(uri.getHost()));
            return reject(exchange, HttpStatus.FORBIDDEN, "host not whitelisted");
        }
        return posterClient
                .get()
                .uri(uri)
                .exchangeToMono(
                        upstream -> {
                            forwardedRequests.increment();
                            upstreamStatusCounter(upstream.statusCode().value()).increment();
                            HttpHeaders responseHeaders = exchange.getResponse().getHeaders();
                            upstream.headers()
                                    .asHttpHeaders()
                                    .forEach(
                                            (name, values) -> {
                                                if (FORWARDED_RESPONSE_HEADERS.contains(
                                                        name.toLowerCase())) {
                                                    responseHeaders.put(name, values);
                                                }
                                            });
                            // Posters are immutable per slug; ask the browser to keep them for a
                            // day so quick scrolls don't refetch.
                            if (!responseHeaders.containsKey(HttpHeaders.CACHE_CONTROL)) {
                                responseHeaders.set(
                                        HttpHeaders.CACHE_CONTROL, "public, max-age=86400");
                            }
                            exchange.getResponse().setStatusCode(upstream.statusCode());
                            Flux<DataBuffer> body = upstream.bodyToFlux(DataBuffer.class);
                            return exchange.getResponse().writeWith(body);
                        });
    }

    static boolean isAllowedHost(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) return false;
        String lower = host.toLowerCase();
        for (String allowed : ALLOWED_HOSTS) {
            if (lower.equals(allowed) || lower.endsWith("." + allowed)) return true;
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
                        Counter.builder(
                                        "orinuno.providers.jutsu.poster_proxy.upstream_status.total")
                                .description("Upstream response status seen by the poster proxy")
                                .tags(Tags.of("status", String.valueOf(s)))
                                .register(meterRegistry));
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        return s.replace('\n', '_').replace('\r', '_');
    }
}
