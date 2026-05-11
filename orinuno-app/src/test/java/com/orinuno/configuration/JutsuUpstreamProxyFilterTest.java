/*
 * JutsuUpstreamProxyFilterTest — ADR 0019 Phase 4.8 invariant.
 *
 * Locks the reverse-proxy contract:
 *   1. Non-jut.su paths pass through to the regular WebFilterChain.
 *   2. Jutsu path prefixes (/api/v1/sources/jutsu, /api/v1/providers/jutsu) get
 *      forwarded to the configured backend.
 *   3. Upstream status code + body propagate back to the caller.
 *   4. Both prefixes are recognised — canonical and legacy.
 *
 * Uses a java.net HttpServer stub so the WebClient round-trip is real.
 */
package com.orinuno.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpResponse;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@DisplayName("JutsuUpstreamProxyFilter — ADR 0019 Phase 4.8 reverse-proxy contract")
class JutsuUpstreamProxyFilterTest {

    private HttpServer backend;
    private AtomicReference<HttpHandler> currentHandler;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        currentHandler = new AtomicReference<>(noop());
        backend.createContext("/", exchange -> currentHandler.get().handle(exchange));
        backend.start();
        baseUrl = "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.stop(0);
        }
    }

    @Test
    @DisplayName("non-jutsu path falls through to the WebFilterChain unchanged")
    void nonJutsuPathFallsThrough() {
        JutsuUpstreamProxyFilter filter = newFilter();
        AtomicReference<Boolean> chainInvoked = new AtomicReference<>(false);
        WebFilterChain chain =
                exchange -> {
                    chainInvoked.set(true);
                    return Mono.empty();
                };

        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/kodik/list"));

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked.get())
                .as("filter must call chain.filter for non-jutsu paths")
                .isTrue();
    }

    @Test
    @DisplayName("/api/v1/sources/jutsu/* forwards to backend")
    void canonicalPathProxies() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        currentHandler.set(
                exchange -> {
                    capturedPath.set(exchange.getRequestURI().getPath());
                    sendJson(exchange, 200, "{\"results\":[]}");
                });

        JutsuUpstreamProxyFilter filter = newFilter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/sources/jutsu/catalog?page=1"));

        filter.filter(exchange, noopChain()).block();

        MockServerHttpResponse response = exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedPath.get()).isEqualTo("/api/v1/sources/jutsu/catalog");
    }

    @Test
    @DisplayName("/api/v1/providers/jutsu/stream (legacy) also proxies")
    void legacyStreamPathProxies() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        currentHandler.set(
                exchange -> {
                    capturedPath.set(exchange.getRequestURI().getPath());
                    sendJson(exchange, 200, "{}");
                });

        JutsuUpstreamProxyFilter filter = newFilter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/providers/jutsu/stream?u=test"));

        filter.filter(exchange, noopChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedPath.get()).isEqualTo("/api/v1/providers/jutsu/stream");
    }

    @Test
    @DisplayName("upstream non-2xx propagates the status code intact")
    void upstreamErrorStatusPropagates() {
        currentHandler.set(exchange -> sendJson(exchange, 502, "{\"error\":\"upstream\"}"));

        JutsuUpstreamProxyFilter filter = newFilter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/sources/jutsu/anime/naruto"));

        filter.filter(exchange, noopChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    private JutsuUpstreamProxyFilter newFilter() {
        return new JutsuUpstreamProxyFilter(WebClient.builder(), baseUrl);
    }

    private static WebFilterChain noopChain() {
        return exchange -> Mono.empty();
    }

    private static HttpHandler noop() {
        return exchange -> sendJson(exchange, 404, "{}");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
