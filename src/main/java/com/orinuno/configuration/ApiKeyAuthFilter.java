package com.orinuno.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter implements WebFilter {

    private static final String API_KEY_HEADER = "X-API-KEY";

    private final OrinunoProperties properties;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String configuredKey = properties.getSecurity().getApiKey();
        if (configuredKey == null || configuredKey.isBlank()) {
            return chain.filter(exchange);
        }

        String path = exchange.getRequest().getPath().value();
        if (!requiresAuth(path)) {
            return chain.filter(exchange);
        }

        String providedKey = exchange.getRequest().getHeaders().getFirst(API_KEY_HEADER);
        if (configuredKey.equals(providedKey)) {
            return chain.filter(exchange);
        }

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }

    private boolean requiresAuth(String path) {
        return path.startsWith("/api/v1/parse")
                || path.startsWith("/api/v1/export")
                || path.startsWith("/api/v1/content")
                || path.startsWith("/api/v1/download");
    }
}
