package com.orinuno.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.ConsumptionProbe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Inbound contract enforcement on {@code POST /api/v1/parse/requests}:
 *
 * <ul>
 *   <li>{@code X-Created-By} must be present and non-blank — otherwise {@code 400}.
 *   <li>Per-consumer (X-Created-By) rate limit via {@link ParseInboundRateLimiter} — otherwise
 *       {@code 429} with a {@code Retry-After} header.
 * </ul>
 *
 * Runs ahead of {@link ApiKeyAuthFilter} so that misconfigured callers get a deterministic 400
 * before the api-key check, which is essential for parser-kodik bring-up. See
 * operations/parser-kodik-integration §2.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ParseInboundRateLimitFilter implements WebFilter {

    static final String CREATED_BY_HEADER = "X-Created-By";
    private static final String PARSE_REQUESTS_PATH = "/api/v1/parse/requests";

    private final ParseInboundRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public ParseInboundRateLimitFilter(
            ParseInboundRateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!isParseRequestSubmit(request)) {
            return chain.filter(exchange);
        }

        String createdBy = request.getHeaders().getFirst(CREATED_BY_HEADER);
        if (createdBy == null || createdBy.isBlank()) {
            return writeError(
                    exchange.getResponse(),
                    HttpStatus.BAD_REQUEST,
                    "X-Created-By header is required and must be non-blank");
        }

        if (!rateLimiter.isEnabled()) {
            return chain.filter(exchange);
        }

        ConsumptionProbe probe = rateLimiter.tryConsume(createdBy.trim());
        if (probe.isConsumed()) {
            return chain.filter(exchange);
        }

        long retryAfterSeconds =
                Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        return writeError(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                "Inbound rate limit exceeded for consumer="
                        + createdBy.trim()
                        + " (budget="
                        + rateLimiter.getRequestsPerMinute()
                        + "/min). Retry-After "
                        + retryAfterSeconds
                        + "s.");
    }

    private boolean isParseRequestSubmit(ServerHttpRequest request) {
        if (!HttpMethod.POST.equals(request.getMethod())) {
            return false;
        }
        String path = request.getPath().value();
        return PARSE_REQUESTS_PATH.equals(path)
                || PARSE_REQUESTS_PATH.equals(stripTrailingSlash(path));
    }

    private static String stripTrailingSlash(String path) {
        if (path == null || path.length() <= 1) return path;
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private Mono<Void> writeError(ServerHttpResponse response, HttpStatus status, String message) {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status.value());
        body.put("error", message);
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize inbound-filter error body: {}", e.toString());
            payload =
                    ("{\"status\":" + status.value() + ",\"error\":\"" + message + "\"}")
                            .getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(payload)));
    }
}
