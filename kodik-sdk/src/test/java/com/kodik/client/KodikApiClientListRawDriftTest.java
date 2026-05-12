package com.kodik.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.kodik.client.dto.KodikListRequest;
import com.kodik.drift.DriftDetector;
import com.kodik.drift.DriftSamplingProperties;
import com.kodik.token.KodikFunction;
import com.kodik.token.KodikTokenConfig;
import com.kodik.token.KodikTokenEntry;
import com.kodik.token.KodikTokenRegistry;
import com.kodik.token.KodikTokenTier;
import java.nio.file.Path;
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

@DisplayName("KodikApiClient.listRaw — drift detection on every page")
class KodikApiClientListRawDriftTest {

    private static final int RATE_LIMIT_PER_MINUTE = 100_000;

    @TempDir Path tempDir;

    private KodikTokenConfig config;
    private KodikTokenRegistry registry;
    private KodikApiRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        Path tokenFile = tempDir.resolve("kodik_tokens.json");
        config =
                KodikTokenConfig.builder()
                        .tokenFile(tokenFile.toString())
                        .bootstrapFromEnv(false)
                        .autoDiscoveryEnabled(false)
                        .build();

        registry = new KodikTokenRegistry(config, () -> null);
        registry.init();
        registry.register(fullScope("tok"), KodikTokenTier.STABLE);

        rateLimiter = new KodikApiRateLimiter(RATE_LIMIT_PER_MINUTE);
    }

    @Test
    @DisplayName("first call records envelope drift on unknown root field")
    void detectsEnvelopeDrift() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":0,"results":[],
                                         "experimental_flag":"yes"}"""));
        KodikResponseMapper mapper = new KodikResponseMapper();
        KodikApiClient client = buildClient(webClient, mapper);

        client.listRaw(KodikListRequest.builder().limit(1).build()).block();

        assertThat(mapper.getDetectedDrifts()).containsKey("KodikSearchResponse");
        assertThat(mapper.getDetectedDrifts().get("KodikSearchResponse").unknownFields())
                .contains("experimental_flag");
    }

    @Test
    @DisplayName("item-level drift is sampled on the first N results")
    void detectsItemDrift() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":1,"results":[
                                          {"id":"a","title":"A","mystery_field":"v"}
                                        ]}"""));
        KodikResponseMapper mapper = new KodikResponseMapper();
        KodikApiClient client = buildClient(webClient, mapper);

        client.listRaw(KodikListRequest.builder().limit(1).build()).block();

        assertThat(mapper.getDetectedDrifts()).containsKey("KodikSearchResponse.Result");
        assertThat(mapper.getDetectedDrifts().get("KodikSearchResponse.Result").unknownFields())
                .contains("mystery_field");
    }

    @Test
    @DisplayName("next_page response also goes through drift detection")
    void detectsDriftOnNextPage() {
        WebClient webClient =
                stubWebClient(
                        (method, uri) ->
                                uri.contains("page=2")
                                        ? respond(
                                                HttpStatus.OK,
                                                """
                                                {"time":"1ms","total":1,"results":[],
                                                 "second_page_only":"yes"}""")
                                        : respond(
                                                HttpStatus.OK,
                                                "{\"time\":\"1ms\",\"total\":1,\"results\":[]}"));
        KodikResponseMapper mapper = new KodikResponseMapper();
        KodikApiClient client = buildClient(webClient, mapper);

        client.listRaw(KodikListRequest.builder().limit(1).build()).block();
        assertThat(mapper.getDetectedDrifts()).isEmpty();

        client.listRaw(
                        KodikListRequest.builder()
                                .nextPageUrl("http://localhost/list?token=tok&page=2")
                                .build())
                .block();

        assertThat(mapper.getDetectedDrifts()).containsKey("KodikSearchResponse");
        assertThat(mapper.getDetectedDrifts().get("KodikSearchResponse").unknownFields())
                .contains("second_page_only");
    }

    @Test
    @DisplayName("disabled detector keeps listRaw working but records no drift")
    void disabledDetectorKeepsResponseFlow() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient =
                stubWebClient(
                        (m, u) -> {
                            calls.incrementAndGet();
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":1,"results":[
                                      {"id":"a","mystery":"v"}
                                    ],"runtime_only":"v"}""");
                        });
        DriftSamplingProperties cfg = new DriftSamplingProperties();
        cfg.setEnabled(false);
        KodikResponseMapper mapper = new KodikResponseMapper(new DriftDetector(cfg));
        KodikApiClient client = buildClient(webClient, mapper);

        client.listRaw(KodikListRequest.builder().limit(1).build()).block();

        assertThat(calls.get()).isEqualTo(1);
        assertThat(mapper.getDetectedDrifts()).isEmpty();
    }

    private KodikApiClient buildClient(WebClient webClient, KodikResponseMapper mapper) {
        return new KodikApiClient(webClient, config, mapper, rateLimiter, registry);
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
