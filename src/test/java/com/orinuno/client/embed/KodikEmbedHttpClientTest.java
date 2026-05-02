package com.orinuno.client.embed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.client.KodikApiRateLimiter;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.token.KodikFunction;
import com.orinuno.token.KodikTokenException;
import com.orinuno.token.KodikTokenRegistry;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

class KodikEmbedHttpClientTest {

    private HttpServer server;
    private WebClient kodikApiWebClient;
    private OrinunoProperties properties;
    private KodikTokenRegistry tokenRegistry;
    private KodikApiRateLimiter rateLimiter;
    private AtomicReference<HttpHandler> currentHandler;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        currentHandler = new AtomicReference<>(noop());
        server.createContext("/get-player", exchange -> currentHandler.get().handle(exchange));
        server.start();

        kodikApiWebClient =
                WebClient.builder()
                        .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                        .build();

        properties = new OrinunoProperties();
        properties.getKodik().setTokenFailoverMaxAttempts(3);

        tokenRegistry = org.mockito.Mockito.mock(KodikTokenRegistry.class);
        rateLimiter = org.mockito.Mockito.mock(KodikApiRateLimiter.class);

        when(rateLimiter.wrapWithRateLimit(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Happy path: forwards correct query params and returns parsed body")
    void happyPath() {
        when(tokenRegistry.currentToken(KodikFunction.GET_INFO)).thenReturn("token-A");

        AtomicReference<Map<String, String>> capturedQuery = new AtomicReference<>();
        currentHandler.set(
                jsonHandler(
                        capturedQuery,
                        200,
                        "{\"found\": true,"
                                + " \"link\":"
                                + " \"//kodikplayer.com/serial/73959/abc/720p\"}"));

        KodikEmbedHttpClient client = newClient();

        StepVerifier.create(client.getPlayerRaw(KodikIdType.SHIKIMORI, "20"))
                .assertNext(
                        body -> {
                            assertThat(body)
                                    .containsEntry("found", true)
                                    .containsEntry(
                                            "link", "//kodikplayer.com/serial/73959/abc/720p");
                        })
                .verifyComplete();

        Map<String, String> query = capturedQuery.get();
        assertThat(query)
                .containsEntry("title", "Player")
                .containsEntry("hasPlayer", "false")
                .containsEntry("token", "token-A")
                .containsEntry("shikimoriID", "20");
        assertThat(query.get("url"))
                .startsWith("https://kodikdb.com/find-player")
                .contains("shikimoriID=20");

        verify(rateLimiter, times(1)).wrapWithRateLimit(any());
    }

    @Test
    @DisplayName("Happy path: imdb id key uses imdbID and forwards normalised tt-prefixed id")
    void happyPathImdb() {
        when(tokenRegistry.currentToken(KodikFunction.GET_INFO)).thenReturn("token-A");

        AtomicReference<Map<String, String>> capturedQuery = new AtomicReference<>();
        currentHandler.set(
                jsonHandler(
                        capturedQuery,
                        200,
                        "{\"found\": true, \"link\": \"//kodikplayer.com/video/1/abc/720p\"}"));

        KodikEmbedHttpClient client = newClient();

        StepVerifier.create(client.getPlayerRaw(KodikIdType.IMDB, "tt0903747"))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(capturedQuery.get()).containsEntry("imdbID", "tt0903747");
    }

    @Test
    @DisplayName(
            "Token failover: invalid-token error retries with next token, second succeeds, registry"
                    + " is marked invalid for first")
    void tokenFailoverRetriesWithNextToken() {
        when(tokenRegistry.currentToken(KodikFunction.GET_INFO)).thenReturn("token-A", "token-B");

        java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Map<String, String>> capturedQuery = new AtomicReference<>();
        currentHandler.set(
                exchange -> {
                    capturedQuery.set(parseQuery(exchange.getRequestURI().getRawQuery()));
                    int attempt = calls.incrementAndGet();
                    String body =
                            (attempt == 1)
                                    ? "{\"error\": \"Отсутствует или неверный токен\"}"
                                    : "{\"found\": true, \"link\":"
                                            + " \"//kodikplayer.com/serial/1/abc/720p\"}";
                    sendJson(exchange, 200, body);
                });

        KodikEmbedHttpClient client = newClient();

        StepVerifier.create(client.getPlayerRaw(KodikIdType.SHIKIMORI, "20"))
                .assertNext(body -> assertThat(body).containsEntry("found", true))
                .verifyComplete();

        assertThat(calls.get()).isEqualTo(2);
        verify(tokenRegistry).markInvalid("token-A", KodikFunction.GET_INFO);
    }

    @Test
    @DisplayName("Token failover stops after tokenFailoverMaxAttempts and surfaces TokenRejected")
    void tokenFailoverExhausted() {
        properties.getKodik().setTokenFailoverMaxAttempts(2);
        when(tokenRegistry.currentToken(KodikFunction.GET_INFO)).thenReturn("token-A", "token-B");

        currentHandler.set(
                exchange ->
                        sendJson(exchange, 200, "{\"error\": \"Отсутствует или неверный токен\"}"));

        KodikEmbedHttpClient client = newClient();

        StepVerifier.create(client.getPlayerRaw(KodikIdType.SHIKIMORI, "20"))
                .expectError(KodikTokenException.TokenRejectedException.class)
                .verify();

        verify(tokenRegistry).markInvalid("token-A", KodikFunction.GET_INFO);
        verify(tokenRegistry).markInvalid("token-B", KodikFunction.GET_INFO);
    }

    @Test
    @DisplayName("Empty token registry surfaces NoWorkingTokenException without HTTP traffic")
    void noTokenAvailable() {
        when(tokenRegistry.currentToken(KodikFunction.GET_INFO))
                .thenThrow(new KodikTokenException.NoWorkingTokenException("empty"));

        currentHandler.set(
                exchange -> {
                    throw new AssertionError("HTTP must not be called when registry is empty");
                });

        KodikEmbedHttpClient client = newClient();

        StepVerifier.create(client.getPlayerRaw(KodikIdType.SHIKIMORI, "20"))
                .expectError(KodikTokenException.NoWorkingTokenException.class)
                .verify();
    }

    @Test
    @DisplayName("Non-token error responses are returned as-is for the service layer to map")
    void nonTokenErrorPassedThrough() {
        when(tokenRegistry.currentToken(KodikFunction.GET_INFO)).thenReturn("token-A");
        currentHandler.set(
                exchange -> sendJson(exchange, 200, "{\"error\": \"Some upstream issue\"}"));

        KodikEmbedHttpClient client = newClient();

        StepVerifier.create(client.getPlayerRaw(KodikIdType.SHIKIMORI, "20"))
                .assertNext(body -> assertThat(body).containsEntry("error", "Some upstream issue"))
                .verifyComplete();

        verify(tokenRegistry, org.mockito.Mockito.never())
                .markInvalid(org.mockito.ArgumentMatchers.anyString(), any(KodikFunction.class));
    }

    private KodikEmbedHttpClient newClient() {
        return new KodikEmbedHttpClient(kodikApiWebClient, properties, tokenRegistry, rateLimiter);
    }

    private static HttpHandler noop() {
        return exchange -> {
            throw new AssertionError("Unexpected HTTP request");
        };
    }

    private static HttpHandler jsonHandler(
            AtomicReference<Map<String, String>> queryCapture, int status, String body) {
        return exchange -> {
            queryCapture.set(parseQuery(exchange.getRequestURI().getRawQuery()));
            sendJson(exchange, status, body);
        };
    }

    private static void sendJson(HttpExchange exchange, int status, String body)
            throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                result.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
