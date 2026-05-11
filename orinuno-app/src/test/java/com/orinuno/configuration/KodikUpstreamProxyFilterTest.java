/*
 * KodikUpstreamProxyFilterTest — ADR 0018 Phase 2.8 invariant.
 *
 * Locks the reverse-proxy contract:
 *   1. Non-Kodik paths pass through to the regular WebFilterChain (orinuno-app local
 *      controllers continue to serve them).
 *   2. Kodik path prefixes (/api/v1/kodik, /embed, /reference, /source-events) get
 *      forwarded to the configured backend with method + headers + body preserved.
 *   3. Upstream status code + body propagate back to the original caller.
 *
 * Uses a tiny java.net HttpServer as the backend stub so we exercise the WebClient
 * end-to-end without needing Spring's test context.
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
import java.util.Map;
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

@DisplayName("KodikUpstreamProxyFilter — ADR 0018 Phase 2.8 reverse-proxy contract")
class KodikUpstreamProxyFilterTest {

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
    @DisplayName("non-Kodik path falls through to the WebFilterChain unchanged")
    void nonKodikPathFallsThrough() {
        KodikUpstreamProxyFilter filter = newFilter();
        AtomicReference<Boolean> chainInvoked = new AtomicReference<>(false);
        WebFilterChain chain =
                exchange -> {
                    chainInvoked.set(true);
                    return Mono.empty();
                };

        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/health"));

        filter.filter(exchange, chain).block();

        assertThat(chainInvoked.get())
                .as("filter must call chain.filter for non-Kodik paths")
                .isTrue();
    }

    @Test
    @DisplayName(
            "Kodik path forwards to backend with method + query preserved; upstream body relayed")
    void kodikPathProxiesToBackend() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        AtomicReference<String> capturedQuery = new AtomicReference<>();
        AtomicReference<String> capturedMethod = new AtomicReference<>();
        currentHandler.set(
                exchange -> {
                    capturedPath.set(exchange.getRequestURI().getPath());
                    capturedQuery.set(exchange.getRequestURI().getRawQuery());
                    capturedMethod.set(exchange.getRequestMethod());
                    sendJson(exchange, 200, "{\"results\":[],\"total\":0}");
                });

        KodikUpstreamProxyFilter filter = newFilter();
        WebFilterChain chain =
                exchange -> Mono.error(new AssertionError("chain.filter must NOT be called"));

        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/kodik/list?limit=1&with_episodes=true"));

        filter.filter(exchange, chain).block();

        MockServerHttpResponse response = exchange.getResponse();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedMethod.get()).isEqualTo("GET");
        assertThat(capturedPath.get()).isEqualTo("/api/v1/kodik/list");
        assertThat(capturedQuery.get()).isEqualTo("limit=1&with_episodes=true");
    }

    @Test
    @DisplayName(
            "upstream non-2xx propagates the status code intact (e.g. 502 when source-kodik is"
                    + " bad)")
    void upstreamErrorStatusPropagates() {
        currentHandler.set(exchange -> sendJson(exchange, 502, "{\"error\":\"upstream\"}"));

        KodikUpstreamProxyFilter filter = newFilter();
        MockServerWebExchange exchange =
                MockServerWebExchange.from(
                        MockServerHttpRequest.get("/api/v1/source-events/ready?limit=1"));

        filter.filter(exchange, noopChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("each Kodik path family is recognised — embed, reference, source-events")
    void allFourPrefixesProxy() {
        AtomicReference<String> capturedPath = new AtomicReference<>();
        currentHandler.set(
                exchange -> {
                    capturedPath.set(exchange.getRequestURI().getPath());
                    sendJson(exchange, 200, "{}");
                });

        KodikUpstreamProxyFilter filter = newFilter();
        for (String path :
                java.util.List.of(
                        "/api/v1/kodik/list",
                        "/api/v1/embed/shikimori/20",
                        "/api/v1/reference/genres",
                        "/api/v1/source-events/ready")) {
            capturedPath.set(null);
            MockServerWebExchange ex = MockServerWebExchange.from(MockServerHttpRequest.get(path));
            filter.filter(ex, noopChain()).block();
            assertThat(capturedPath.get()).as("filter must proxy path: %s", path).isEqualTo(path);
        }
    }

    private KodikUpstreamProxyFilter newFilter() {
        return new KodikUpstreamProxyFilter(WebClient.builder(), baseUrl);
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

    @SuppressWarnings("unused")
    private static Map<String, String> noQuery() {
        return Map.of();
    }
}
