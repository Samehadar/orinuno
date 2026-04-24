package com.orinuno.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.client.KodikResponseMapper;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.drift.DriftDetector;
import com.orinuno.drift.DriftSamplingProperties;
import java.nio.file.Path;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@DisplayName("KodikTokenValidator — drift sampling on probe responses")
class KodikTokenValidatorDriftSamplingTest {

    @TempDir Path tempDir;

    private OrinunoProperties properties;
    private KodikTokenRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new OrinunoProperties();
        properties.getKodik().setTokenFile(tempDir.resolve("kodik_tokens.json").toString());
        properties.getKodik().setBootstrapFromEnv(false);
        properties.getKodik().setAutoDiscoveryEnabled(false);

        ObjectProvider<KodikTokenAutoDiscovery> noDiscovery =
                (ObjectProvider<KodikTokenAutoDiscovery>) mock(ObjectProvider.class);
        when(noDiscovery.getIfAvailable()).thenReturn(null);
        registry = new KodikTokenRegistry(properties, noDiscovery);
        registry.init();
    }

    @Test
    @DisplayName("successful probe feeds the response into drift detection")
    void successfulProbeSamplesDrift() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":1,"results":[
                                          {"id":"x","title":"t"}
                                        ],"probe_only_field":"yes"}"""));
        KodikResponseMapper mapper = new KodikResponseMapper();
        KodikTokenValidator validator =
                new KodikTokenValidator(webClient, properties, registry, mapper);

        boolean ok = validator.probe("/search", params("tok", "title", "x"), "tok", "probe");

        assertThat(ok).isTrue();
        assertThat(mapper.getDetectedDrifts()).containsKey("KodikSearchResponse");
        assertThat(mapper.getDetectedDrifts().get("KodikSearchResponse").unknownFields())
                .contains("probe_only_field");
    }

    @Test
    @DisplayName("rejected token response is NOT sampled for drift")
    void rejectedTokenSkipsDriftSampling() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.OK,
                                        "{\"error\":\""
                                                + KodikTokenValidator.INVALID_TOKEN_ERROR
                                                + "\",\"unexpected\":\"drift-ish\"}"));
        KodikResponseMapper mapper = new KodikResponseMapper();
        KodikTokenValidator validator =
                new KodikTokenValidator(webClient, properties, registry, mapper);

        boolean ok = validator.probe("/search", params("bad", "title", "x"), "bad", "probe");

        assertThat(ok).isFalse();
        assertThat(mapper.getDetectedDrifts()).isEmpty();
        assertThat(mapper.getTotalChecks().get()).isZero();
    }

    @Test
    @DisplayName("HTTP error skips drift sampling without throwing")
    void httpErrorSkipsDrift() {
        WebClient webClient =
                stubWebClient((m, u) -> respond(HttpStatus.INTERNAL_SERVER_ERROR, "{\"oops\":1}"));
        KodikResponseMapper mapper = new KodikResponseMapper();
        KodikTokenValidator validator =
                new KodikTokenValidator(webClient, properties, registry, mapper);

        boolean ok = validator.probe("/search", params("tok", "title", "x"), "tok", "probe");

        assertThat(ok).isFalse();
        assertThat(mapper.getTotalChecks().get()).isZero();
    }

    @Test
    @DisplayName("disabled detector keeps probe success intact and records nothing")
    void disabledDetectorStillAllowsProbe() {
        WebClient webClient =
                stubWebClient(
                        (m, u) ->
                                respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":0,"results":[],
                                         "unexpected":"v"}"""));
        DriftSamplingProperties cfg = new DriftSamplingProperties();
        cfg.setEnabled(false);
        KodikResponseMapper mapper = new KodikResponseMapper(new DriftDetector(cfg));
        KodikTokenValidator validator =
                new KodikTokenValidator(webClient, properties, registry, mapper);

        boolean ok = validator.probe("/search", params("tok", "title", "x"), "tok", "probe");

        assertThat(ok).isTrue();
        assertThat(mapper.getDetectedDrifts()).isEmpty();
        assertThat(mapper.getTotalChecks().get()).isZero();
    }

    private static MultiValueMap<String, String> params(String token, String key, String value) {
        MultiValueMap<String, String> p = new LinkedMultiValueMap<>();
        p.add("token", token);
        p.add(key, value);
        return p;
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
