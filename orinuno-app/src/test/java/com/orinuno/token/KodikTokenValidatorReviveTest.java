package com.orinuno.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.client.KodikResponseMapper;
import com.orinuno.configuration.OrinunoProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@DisplayName("KodikTokenValidator — DEAD-tier cooldown and auto-revive")
class KodikTokenValidatorReviveTest {

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
    }

    @Nested
    @DisplayName("validateAll() picks up DEAD tokens past the cooldown")
    class CooldownInclusion {

        @Test
        @DisplayName("DEAD token never checked is included and revived on success")
        void neverCheckedIsRevived() throws IOException {
            writeFile(
                    "{\"stable\":[],\"unstable\":[],\"legacy\":[],"
                            + "\"dead\":[{\"value\":\"forgotten\","
                            + "\"functions_availability\":{"
                            + "\"base_search\":false,\"base_search_by_id\":false,"
                            + "\"get_list\":false,\"search\":false,\"search_by_id\":false,"
                            + "\"get_info\":false,\"get_link\":false,"
                            + "\"get_m3u8_playlist_link\":false}}]}");
            registry.init();
            assertThat(registry.tierOf("forgotten")).contains(KodikTokenTier.DEAD);

            KodikTokenValidator validator =
                    new KodikTokenValidator(
                            okWebClient(), properties, registry, new KodikResponseMapper());

            validator.validateAll();

            assertThat(registry.tierOf("forgotten")).contains(KodikTokenTier.UNSTABLE);
            assertThat(registry.countFor(KodikTokenTier.DEAD)).isZero();
            assertThat(registry.currentToken(KodikFunction.BASE_SEARCH)).isEqualTo("forgotten");
        }

        @Test
        @DisplayName("DEAD token last checked beyond cooldown is included")
        void staleDeadIsIncluded() throws IOException {
            properties.getKodik().setDeadRevalidationIntervalMinutes(60);
            writeFile(
                    "{\"stable\":[],\"unstable\":[],\"legacy\":[],"
                            + "\"dead\":[{\"value\":\"stale\","
                            + "\"last_checked\":\""
                            + Instant.now().minus(2, ChronoUnit.HOURS).toString()
                            + "\","
                            + "\"functions_availability\":{"
                            + "\"base_search\":false,\"base_search_by_id\":false,"
                            + "\"get_list\":false,\"search\":false,\"search_by_id\":false,"
                            + "\"get_info\":false,\"get_link\":false,"
                            + "\"get_m3u8_playlist_link\":false}}]}");
            registry.init();

            KodikTokenValidator validator =
                    new KodikTokenValidator(
                            okWebClient(), properties, registry, new KodikResponseMapper());

            validator.validateAll();

            assertThat(registry.tierOf("stale")).contains(KodikTokenTier.UNSTABLE);
        }
    }

    @Nested
    @DisplayName("validateAll() respects the DEAD cooldown window")
    class CooldownEnforcement {

        @Test
        @DisplayName("DEAD token recently checked is skipped (no probe attempts)")
        void freshDeadIsSkipped() throws IOException {
            properties.getKodik().setDeadRevalidationIntervalMinutes(60);
            writeFile(
                    "{\"stable\":[],\"unstable\":[],\"legacy\":[],"
                            + "\"dead\":[{\"value\":\"fresh-dead\","
                            + "\"last_checked\":\""
                            + Instant.now().minus(5, ChronoUnit.MINUTES).toString()
                            + "\","
                            + "\"functions_availability\":{"
                            + "\"base_search\":false,\"base_search_by_id\":false,"
                            + "\"get_list\":false,\"search\":false,\"search_by_id\":false,"
                            + "\"get_info\":false,\"get_link\":false,"
                            + "\"get_m3u8_playlist_link\":false}}]}");
            registry.init();
            int[] callCount = {0};
            WebClient counting =
                    stubWebClient(
                            (m, u) -> {
                                callCount[0]++;
                                return Mono.just(okResponse());
                            });
            KodikTokenValidator validator =
                    new KodikTokenValidator(
                            counting, properties, registry, new KodikResponseMapper());

            validator.validateAll();

            assertThat(callCount[0]).isZero();
            assertThat(registry.tierOf("fresh-dead")).contains(KodikTokenTier.DEAD);
        }
    }

    @Nested
    @DisplayName("validateAll() leaves still-rejected DEAD tokens in DEAD")
    class StillDead {

        @Test
        @DisplayName("DEAD token whose probes still fail stays in DEAD")
        void stillDeadStaysDead() throws IOException {
            writeFile(
                    "{\"stable\":[],\"unstable\":[],\"legacy\":[],"
                            + "\"dead\":[{\"value\":\"still-bad\","
                            + "\"functions_availability\":{"
                            + "\"base_search\":false,\"base_search_by_id\":false,"
                            + "\"get_list\":false,\"search\":false,\"search_by_id\":false,"
                            + "\"get_info\":false,\"get_link\":false,"
                            + "\"get_m3u8_playlist_link\":false}}]}");
            registry.init();
            WebClient rejecting =
                    stubWebClient(
                            (m, u) ->
                                    Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            "Content-Type",
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(
                                                            "{\"error\":\""
                                                                    + KodikTokenValidator
                                                                            .INVALID_TOKEN_ERROR
                                                                    + "\"}")
                                                    .build()));
            KodikTokenValidator validator =
                    new KodikTokenValidator(
                            rejecting, properties, registry, new KodikResponseMapper());

            validator.validateAll();

            assertThat(registry.tierOf("still-bad")).contains(KodikTokenTier.DEAD);
            assertThat(registry.countFor(KodikTokenTier.UNSTABLE)).isZero();
        }
    }

    private void writeFile(String contents) throws IOException {
        Files.writeString(Path.of(properties.getKodik().getTokenFile()), contents);
    }

    private static WebClient okWebClient() {
        return stubWebClient((m, u) -> Mono.just(okResponse()));
    }

    private static WebClient stubWebClient(
            BiFunction<String, String, Mono<ClientResponse>> responder) {
        ExchangeFunction exchange =
                request -> responder.apply(request.method().name(), request.url().toString());
        return WebClient.builder().baseUrl("http://localhost").exchangeFunction(exchange).build();
    }

    private static ClientResponse okResponse() {
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"time\":\"1ms\",\"total\":1,\"results\":[{\"id\":\"x\",\"title\":\"t\"}]}")
                .build();
    }
}
