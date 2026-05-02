package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.client.dto.reference.KodikCountryDto;
import com.orinuno.client.dto.reference.KodikGenreDto;
import com.orinuno.client.dto.reference.KodikQualityDto;
import com.orinuno.client.dto.reference.KodikTranslationDto;
import com.orinuno.client.dto.reference.KodikYearDto;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.token.KodikFunction;
import com.orinuno.token.KodikTokenAutoDiscovery;
import com.orinuno.token.KodikTokenEntry;
import com.orinuno.token.KodikTokenRegistry;
import com.orinuno.token.KodikTokenTier;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
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
import reactor.test.StepVerifier;

@DisplayName("KodikApiClient — typed reference endpoints")
class KodikApiClientReferenceTest {

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
    @DisplayName("translations() deserializes envelope and items into KodikTranslationDto")
    void translations() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            calls.incrementAndGet();
                            assertThat(uri).contains("/translations/v2");
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":2,"results":[
                                      {"id":610,"title":"AniLibria.TV","count":1200},
                                      {"id":609,"title":"SovetRomantica","count":540}
                                    ]}""");
                        });

        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.translations())
                .assertNext(
                        env -> {
                            assertThat(env.getTotal()).isEqualTo(2);
                            assertThat(env.getResults())
                                    .extracting(
                                            KodikTranslationDto::id,
                                            KodikTranslationDto::title,
                                            KodikTranslationDto::count)
                                    .containsExactly(
                                            org.assertj.core.groups.Tuple.tuple(
                                                    610, "AniLibria.TV", 1200),
                                            org.assertj.core.groups.Tuple.tuple(
                                                    609, "SovetRomantica", 540));
                        })
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("genres() returns KodikGenreDto items")
    void genres() {
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            assertThat(uri).contains("/genres");
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":1,"results":[
                                      {"title":"Аниме","count":8800}
                                    ]}""");
                        });
        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.genres())
                .assertNext(
                        env -> {
                            assertThat(env.getResults())
                                    .singleElement()
                                    .extracting(KodikGenreDto::title, KodikGenreDto::count)
                                    .containsExactly("Аниме", 8800);
                        })
                .verifyComplete();
    }

    @Test
    @DisplayName("countries() returns KodikCountryDto items")
    void countries() {
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            assertThat(uri).contains("/countries");
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":1,"results":[
                                      {"title":"Япония","count":3300}
                                    ]}""");
                        });
        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.countries())
                .assertNext(
                        env ->
                                assertThat(env.getResults())
                                        .singleElement()
                                        .extracting(KodikCountryDto::title)
                                        .isEqualTo("Япония"))
                .verifyComplete();
    }

    @Test
    @DisplayName("years() preserves integer year field (not title)")
    void years() {
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            assertThat(uri).contains("/years");
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":2,"results":[
                                      {"year":2024,"count":720},
                                      {"year":2023,"count":890}
                                    ]}""");
                        });
        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.years())
                .assertNext(
                        env ->
                                assertThat(env.getResults())
                                        .extracting(KodikYearDto::year, KodikYearDto::count)
                                        .containsExactly(
                                                org.assertj.core.groups.Tuple.tuple(2024, 720),
                                                org.assertj.core.groups.Tuple.tuple(2023, 890)))
                .verifyComplete();
    }

    @Test
    @DisplayName("qualities() hits /qualities/v2 and returns KodikQualityDto items")
    void qualities() {
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            assertThat(uri).contains("/qualities/v2");
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":2,"results":[
                                      {"title":"HD 720","count":5000},
                                      {"title":"Full HD 1080","count":1200}
                                    ]}""");
                        });
        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.qualities())
                .assertNext(
                        env ->
                                assertThat(env.getResults())
                                        .extracting(KodikQualityDto::title)
                                        .containsExactly("HD 720", "Full HD 1080"))
                .verifyComplete();
    }

    @Test
    @DisplayName("typed envelope round-trip wires KodikResponseMapper drift detection")
    void driftDetectorIsInvoked() {
        WebClient webClient =
                stubWebClient(
                        (method, uri) ->
                                respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":1,"results":[
                                          {"title":"X","count":1,"new_field":"surprise"}
                                        ],"unexpected":"yes"}"""));

        KodikResponseMapper mapper = new KodikResponseMapper();
        KodikApiClient client =
                new KodikApiClient(webClient, properties, mapper, rateLimiter, registry);

        client.genres().block();

        assertThat(mapper.getDetectedDrifts()).containsKey("KodikGenreDto");
        assertThat(mapper.getDetectedDrifts()).containsKey("KodikReferenceResponse<KodikGenreDto>");
    }

    // ======================== helpers ========================

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
