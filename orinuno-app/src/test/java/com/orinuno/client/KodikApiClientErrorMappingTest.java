package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.client.dto.KodikSearchRequest;
import com.orinuno.client.exception.KodikRateLimitedException;
import com.orinuno.client.exception.KodikUpstreamException;
import com.orinuno.client.exception.KodikValidationException;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.token.KodikFunction;
import com.orinuno.token.KodikTokenAutoDiscovery;
import com.orinuno.token.KodikTokenEntry;
import com.orinuno.token.KodikTokenRegistry;
import com.orinuno.token.KodikTokenTier;
import java.nio.file.Path;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Verifies that {@link KodikApiClient}'s error pipeline maps Kodik responses to the typed {@link
 * com.orinuno.client.exception.KodikApiException} hierarchy added in API-5.
 */
@DisplayName("KodikApiClient — typed exception mapping (API-5)")
class KodikApiClientErrorMappingTest {

    @TempDir Path tempDir;

    private OrinunoProperties properties;
    private KodikTokenRegistry registry;
    private KodikApiRateLimiter rateLimiter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new OrinunoProperties();
        properties.getParse().setRateLimitPerMinute(100_000);
        properties.getKodik().setTokenFile(tempDir.resolve("kodik_tokens.json").toString());
        properties.getKodik().setBootstrapFromEnv(false);
        properties.getKodik().setAutoDiscoveryEnabled(false);

        ObjectProvider<KodikTokenAutoDiscovery> noDiscovery =
                (ObjectProvider<KodikTokenAutoDiscovery>) mock(ObjectProvider.class);
        when(noDiscovery.getIfAvailable()).thenReturn(null);
        registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();
        registry.register(fullScope("test-token"), KodikTokenTier.STABLE);

        rateLimiter = new KodikApiRateLimiter(properties);
    }

    @Test
    @DisplayName("200 + {error: 'Не указан...'} → KodikValidationException")
    void validationErrorMapsToValidationException() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.OK,
                                        "{\"error\":\"Не указан хотя бы один параметр для"
                                                + " поиска\"}"));
        KodikApiClient client = buildClient(webClient);

        assertThatThrownBy(() -> client.searchRaw(KodikSearchRequest.builder().build()).block())
                .isInstanceOf(KodikValidationException.class)
                .hasMessageContaining("Не указан хотя бы один параметр");
    }

    @Test
    @DisplayName("200 + {error: 'Неправильный тип'} → KodikValidationException")
    void wrongTypeErrorMapsToValidationException() {
        WebClient webClient =
                stubWebClient((m, u) -> respond(HttpStatus.OK, "{\"error\":\"Неправильный тип\"}"));
        KodikApiClient client = buildClient(webClient);

        assertThatThrownBy(
                        () ->
                                client.searchRaw(KodikSearchRequest.builder().title("x").build())
                                        .block())
                .isInstanceOf(KodikValidationException.class);
    }

    @Test
    @DisplayName("HTTP 500 → KodikUpstreamException with status + body preview")
    void server500MapsToUpstreamException() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "<html><body>Boom</body></html>"));
        KodikApiClient client = buildClient(webClient);

        assertThatThrownBy(
                        () ->
                                client.searchRaw(KodikSearchRequest.builder().title("x").build())
                                        .block())
                .isInstanceOf(KodikUpstreamException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("Boom");
    }

    @Test
    @DisplayName("HTTP 429 → KodikRateLimitedException, Retry-After parsed")
    void http429MapsToRateLimitedException() {
        WebClient webClient =
                WebClient.builder()
                        .baseUrl("http://localhost")
                        .exchangeFunction(
                                req ->
                                        Mono.just(
                                                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                                                        .header("Retry-After", "42")
                                                        .header(
                                                                "Content-Type",
                                                                MediaType.APPLICATION_JSON_VALUE)
                                                        .body("")
                                                        .build()))
                        .build();
        KodikApiClient client = buildClient(webClient);

        assertThatThrownBy(
                        () ->
                                client.searchRaw(KodikSearchRequest.builder().title("x").build())
                                        .block())
                .isInstanceOfSatisfying(
                        KodikRateLimitedException.class,
                        ex ->
                                org.assertj.core.api.Assertions.assertThat(
                                                ex.getRetryAfterSeconds())
                                        .isEqualTo(42L));
    }

    @Test
    @DisplayName(
            "Token-rejection still propagates KodikTokenException (token-failover path), NOT a"
                    + " typed validation/upstream exception")
    void tokenRejectionStillFollowsFailoverPath() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.OK,
                                        "{\"error\":\"Отсутствует или неверный токен\"}"));
        KodikApiClient client = buildClient(webClient);

        assertThatThrownBy(
                        () ->
                                client.searchRaw(KodikSearchRequest.builder().title("x").build())
                                        .block())
                .as(
                        "Should be a token-class exception (TokenRejected if loop ran out of"
                                + " attempts, NoWorkingToken if registry was drained mid-loop),"
                                + " NOT a KodikValidationException — token-failover loop owns this"
                                + " case")
                .isInstanceOf(com.orinuno.token.KodikTokenException.class)
                .isNotInstanceOf(KodikValidationException.class)
                .isNotInstanceOf(KodikUpstreamException.class);
    }

    private KodikApiClient buildClient(WebClient webClient) {
        return new KodikApiClient(
                webClient, properties, new KodikResponseMapper(), rateLimiter, registry);
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
}
