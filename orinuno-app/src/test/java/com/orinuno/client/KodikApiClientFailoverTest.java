package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kodik.client.KodikApiRateLimiter;
import com.kodik.client.KodikResponseMapper;
import com.kodik.client.dto.KodikSearchRequest;
import com.kodik.token.KodikFunction;
import com.kodik.token.KodikTokenEntry;
import com.kodik.token.KodikTokenException;
import com.kodik.token.KodikTokenRegistry;
import com.kodik.token.KodikTokenTier;
import com.orinuno.configuration.OrinunoProperties;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("KodikApiClient — token failover behaviour")
class KodikApiClientFailoverTest {

    private static final String INVALID_TOKEN_BODY =
            "{\"time\":\"0ms\",\"error\":\"Отсутствует или неверный токен\"}";
    private static final String OK_BODY = "{\"time\":\"0ms\",\"total\":0,\"results\":[]}";

    @TempDir Path tempDir;

    private OrinunoProperties properties;
    private KodikTokenRegistry registry;
    private KodikApiRateLimiter passthroughLimiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new OrinunoProperties();
        properties.getParse().setRateLimitPerMinute(100_000);
        properties.getKodik().setTokenFile(tempDir.resolve("kodik_tokens.json").toString());
        properties.getKodik().setBootstrapFromEnv(false);
        properties.getKodik().setAutoDiscoveryEnabled(false);
        properties.getKodik().setTokenFailoverMaxAttempts(3);

        registry =
                new KodikTokenRegistry(
                        com.orinuno.token.TokenConfigTestSupport.toConfig(properties), () -> null);
        registry.init();

        passthroughLimiter = new KodikApiRateLimiter(properties.getParse().getRateLimitPerMinute());
    }

    @Test
    @DisplayName("retries with next token when Kodik rejects the first")
    void retriesWithNextToken() {
        registry.register(fullScope("first"), KodikTokenTier.STABLE);
        registry.register(fullScope("second"), KodikTokenTier.STABLE);

        AtomicInteger calls = new AtomicInteger();
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            calls.incrementAndGet();
                            String token = extractToken(uri);
                            if ("first".equals(token)) {
                                return respond(HttpStatus.OK, INVALID_TOKEN_BODY);
                            }
                            return respond(HttpStatus.OK, OK_BODY);
                        });

        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.searchRaw(KodikSearchRequest.builder().title("test").build()))
                .assertNext(
                        body -> {
                            assertThat(body).containsKey("results");
                            assertThat(body.get("error")).isNull();
                        })
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(
                        registry.findEntry("first")
                                .orElseThrow()
                                .isAvailableFor(KodikFunction.BASE_SEARCH))
                .isFalse();
    }

    @Test
    @DisplayName("fails with TokenRejectedException after exhausting attempts")
    void failsAfterExhaustion() {
        registry.register(fullScope("a"), KodikTokenTier.STABLE);
        registry.register(fullScope("b"), KodikTokenTier.STABLE);
        properties.getKodik().setTokenFailoverMaxAttempts(2);

        WebClient webClient =
                stubWebClient((method, uri) -> respond(HttpStatus.OK, INVALID_TOKEN_BODY));
        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.searchRaw(KodikSearchRequest.builder().title("test").build()))
                .expectError(KodikTokenException.TokenRejectedException.class)
                .verify();
    }

    @Test
    @DisplayName("fails fast when no token is available for the function")
    void failsWithoutToken() {
        WebClient webClient = stubWebClient((method, uri) -> respond(HttpStatus.OK, OK_BODY));
        KodikApiClient client = buildClient(webClient);

        assertThatThrownBy(
                        () ->
                                client.searchRaw(KodikSearchRequest.builder().title("x").build())
                                        .block())
                .isInstanceOf(KodikTokenException.NoWorkingTokenException.class);
    }

    // ======================== helpers ========================

    private KodikApiClient buildClient(WebClient webClient) {
        return new KodikApiClient(
                webClient, properties, new KodikResponseMapper(), passthroughLimiter, registry);
    }

    private static KodikTokenEntry fullScope(String value) {
        KodikTokenEntry entry = KodikTokenEntry.builder().value(value).build();
        for (KodikFunction fn : KodikFunction.values()) {
            entry.setAvailability(fn, true);
        }
        return entry;
    }

    private static WebClient stubWebClient(
            BiFunction<String, String, Mono<ClientResponse>> responder) {
        ExchangeFunction exchange =
                request -> responder.apply(request.method().name(), request.url().toString());
        return WebClient.builder().baseUrl("http://localhost").exchangeFunction(exchange).build();
    }

    private static Mono<ClientResponse> respond(HttpStatus status, String body) {
        return Mono.just(
                ClientResponse.create(status)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body(body)
                        .build());
    }

    private static String extractToken(String uri) {
        List<String> parts = List.of(uri.split("[?&]"));
        for (String part : parts) {
            if (part.startsWith("token=")) {
                return part.substring("token=".length());
            }
        }
        return null;
    }

    // Used for smoke-checking the ObjectMapper wires correctly (optional consumer).
    @SuppressWarnings("unused")
    private static Map<String, Object> readJson(String body) throws Exception {
        return new ObjectMapper()
                .readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
}
