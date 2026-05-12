/*
 * KodikUpstreamProxyFilter — ADR 0018 Phase 2.8.
 *
 * Reverse-proxy in orinuno that forwards Kodik REST routes to the standalone
 * orinuno-source-kodik service. Gated by orinuno.source-kodik.base-url —
 * unset/empty value preserves the legacy behaviour (orinuno-app's own controllers serve
 * the routes). When set, the four Kodik route families are proxied transparently:
 *
 *   - /api/v1/kodik/*           (KodikListController)
 *   - /api/v1/embed/*           (KodikEmbedController)
 *   - /api/v1/reference/*       (ReferenceController)
 *   - /api/v1/source-events/*   (SourceEventController)
 *
 * external URLs stay stable — every existing consumer plus the demo UI keep their current
 * endpoints; orinuno transparently routes to the per-source service.
 */
package com.orinuno.configuration;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "orinuno.source-kodik", name = "base-url")
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class KodikUpstreamProxyFilter implements WebFilter {

    /**
     * Path prefixes routed to orinuno-source-kodik. Order does not matter — first-match wins via
     * short-circuit, and prefixes do not overlap.
     */
    private static final List<String> PROXY_PREFIXES =
            List.of(
                    "/api/v1/kodik/",
                    "/api/v1/embed/",
                    "/api/v1/reference/",
                    "/api/v1/source-events/",
                    // ADR 0021 §C1.2 — Kodik L1 read surface moved to source-kodik in C1.1.
                    // Proxy intercepts before the (now-deleted) orinuno-app ContentController
                    // would have matched; demo UI (demo/src/api/client.ts) sees identical
                    // wire shape because ContentDto is field-for-field unchanged.
                    "/api/v1/content/",
                    // ADR 0021 §C4.2 — denormalised export endpoints moved to source-kodik
                    // (C4.1, commit c4127a7). Same demo UI compatibility story as
                    // /api/v1/content/.
                    "/api/v1/export/");

    /**
     * Hop-by-hop headers we strip on both legs of the proxy per RFC 7230 §6.1 plus a couple of
     * WebFlux-controlled ones (content-length is set by the body publisher).
     */
    private static final List<String> HOP_BY_HOP_HEADERS =
            List.of(
                    "connection",
                    "keep-alive",
                    "proxy-authenticate",
                    "proxy-authorization",
                    "te",
                    "trailer",
                    "transfer-encoding",
                    "upgrade",
                    "content-length",
                    "host");

    private final WebClient backendClient;

    public KodikUpstreamProxyFilter(
            WebClient.Builder builder, @Value("${orinuno.source-kodik.base-url}") String baseUrl) {
        // Codec budget mirrors the kodikApiWebClient — Kodik /list payloads occasionally
        // exceed Spring's default 256 KiB cap and the proxy must not be the bottleneck.
        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.backendClient = builder.baseUrl(baseUrl).exchangeStrategies(strategies).build();
        log.info("Kodik upstream proxy ENABLED — routing Kodik routes to {}", baseUrl);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!shouldProxy(path)) {
            return chain.filter(exchange);
        }
        return proxy(exchange);
    }

    private static boolean shouldProxy(String path) {
        for (String prefix : PROXY_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Void> proxy(ServerWebExchange exchange) {
        var request = exchange.getRequest();
        String pathAndQuery =
                request.getURI().getRawQuery() == null
                        ? request.getURI().getRawPath()
                        : request.getURI().getRawPath() + "?" + request.getURI().getRawQuery();
        log.debug(
                "Proxying Kodik route {} {} to orinuno-source-kodik",
                request.getMethod(),
                pathAndQuery);
        return backendClient
                .method(request.getMethod())
                .uri(pathAndQuery)
                .headers(copyRequestHeaders(request.getHeaders()))
                .body((outputMessage, context) -> outputMessage.writeWith(request.getBody()))
                .exchangeToMono(
                        upstream -> {
                            var response = exchange.getResponse();
                            response.setStatusCode(upstream.statusCode());
                            upstream.headers()
                                    .asHttpHeaders()
                                    .forEach(
                                            (name, values) -> {
                                                if (!isHopByHop(name)) {
                                                    response.getHeaders().put(name, values);
                                                }
                                            });
                            return response.writeWith(
                                    upstream.bodyToFlux(byte[].class)
                                            .map(b -> response.bufferFactory().wrap(b)));
                        });
    }

    private static java.util.function.Consumer<HttpHeaders> copyRequestHeaders(HttpHeaders source) {
        return target ->
                source.forEach(
                        (name, values) -> {
                            if (!isHopByHop(name)) {
                                target.put(name, values);
                            }
                        });
    }

    private static boolean isHopByHop(String headerName) {
        String lower = headerName.toLowerCase(java.util.Locale.ROOT);
        return HOP_BY_HOP_HEADERS.contains(lower);
    }
}
