/*
 * JutsuUpstreamProxyFilter — ADR 0019 Phase 4.8.
 *
 * Reverse-proxy in orinuno that forwards jut.su REST routes to the standalone
 * orinuno-source-jutsu service. Gated by orinuno.source-jutsu.base-url —
 * unset/empty value preserves the legacy behaviour (orinuno-app's own jut.su
 * controllers + schedulers continue to serve the routes). When set, the
 * jut.su route families are proxied transparently:
 *
 *   - /api/v1/sources/jutsu/*       (JutsuApiController + JutsuStreamProxyController canonical)
 *   - /api/v1/providers/jutsu/      (JutsuStreamProxyController legacy alias)
 *
 * /api/v1/source-events/ stays bound to orinuno-source-kodik via the Kodik
 * filter — meter (Phase 4.11) polls each per-source service directly, so the
 * gateway never multiplexes that prefix.
 *
 * external URLs stay stable — demo UI keeps every existing endpoint; orinuno
 * transparently routes to the per-source service. ADR 0019 §"Reverse-proxy
 * prefixes" recommends factoring this into a generic SourceUpstreamProxyFilter
 * when a third source arrives; with only kodik + jutsu, the duplication is fine.
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
@ConditionalOnProperty(prefix = "orinuno.source-jutsu", name = "base-url")
@Order(Ordered.HIGHEST_PRECEDENCE + 11)
public class JutsuUpstreamProxyFilter implements WebFilter {

    /**
     * Path prefixes routed to orinuno-source-jutsu. First-match wins via short-circuit; prefixes do
     * not overlap.
     */
    private static final List<String> PROXY_PREFIXES =
            List.of("/api/v1/sources/jutsu/", "/api/v1/providers/jutsu/");

    /** Hop-by-hop headers stripped on both legs per RFC 7230 §6.1 + WebFlux-controlled ones. */
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

    public JutsuUpstreamProxyFilter(
            WebClient.Builder builder, @Value("${orinuno.source-jutsu.base-url}") String baseUrl) {
        // Codec budget mirrors the Kodik proxy: jut.su catalog pages can run multi-MB during a
        // full crawl and the gateway must not be the bottleneck.
        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        this.backendClient = builder.baseUrl(baseUrl).exchangeStrategies(strategies).build();
        log.info("Jutsu upstream proxy ENABLED — routing jut.su routes to {}", baseUrl);
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
                "Proxying jut.su route {} {} to orinuno-source-jutsu",
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
