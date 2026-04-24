package com.orinuno.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.orinuno.client.dto.KodikListRequest;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.token.KodikFunction;
import com.orinuno.token.KodikTokenAutoDiscovery;
import com.orinuno.token.KodikTokenEntry;
import com.orinuno.token.KodikTokenRegistry;
import com.orinuno.token.KodikTokenTier;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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

@DisplayName("KodikApiClient.listAll — auto-paginating /list helper")
class KodikApiClientListAllTest {

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
        registry.register(fullScope("tok"), KodikTokenTier.STABLE);

        rateLimiter = new KodikApiRateLimiter(properties);
    }

    @Test
    @DisplayName("walks next_page across three pages and emits all items in order")
    void walksThreePages() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            int call = calls.incrementAndGet();
                            if (call == 1) {
                                assertThat(uri).contains("/list");
                                return respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":3,"results":[
                                          {"id":"a","title":"A"}
                                        ],"next_page":"http://localhost/list?token=tok&page=2"}""");
                            }
                            if (call == 2) {
                                assertThat(uri).contains("page=2");
                                return respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":3,"results":[
                                          {"id":"b","title":"B"}
                                        ],"next_page":"http://localhost/list?token=tok&page=3"}""");
                            }
                            assertThat(uri).contains("page=3");
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":3,"results":[
                                      {"id":"c","title":"C"}
                                    ]}""");
                        });

        KodikApiClient client = buildClient(webClient);

        List<Map<String, Object>> all =
                client.listAll(KodikListRequest.builder().limit(1).build()).collectList().block();

        assertThat(all).hasSize(3);
        assertThat(all.stream().map(m -> m.get("id")).toList()).containsExactly("a", "b", "c");
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("single page without next_page emits only that page")
    void singlePage() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            calls.incrementAndGet();
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":2,"results":[
                                      {"id":"x"},{"id":"y"}
                                    ]}""");
                        });

        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.listAll(KodikListRequest.builder().limit(10).build()))
                .expectNextCount(2)
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty results on first page produce empty flux")
    void emptyFirstPage() {
        WebClient webClient =
                stubWebClient(
                        (method, uri) ->
                                respond(
                                        HttpStatus.OK,
                                        "{\"time\":\"1ms\",\"total\":0,\"results\":[]}"));

        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.listAll(KodikListRequest.builder().limit(1).build()))
                .verifyComplete();
    }

    @Test
    @DisplayName("blank next_page terminates iteration without an extra request")
    void blankNextPage() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            calls.incrementAndGet();
                            return respond(
                                    HttpStatus.OK,
                                    """
                                    {"time":"1ms","total":1,"results":[
                                      {"id":"z"}
                                    ],"next_page":""}""");
                        });

        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.listAll(KodikListRequest.builder().limit(1).build()))
                .expectNextCount(1)
                .verifyComplete();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("upstream error on second page surfaces through the flux")
    void errorOnSecondPage() {
        AtomicInteger calls = new AtomicInteger();
        WebClient webClient =
                stubWebClient(
                        (method, uri) -> {
                            int call = calls.incrementAndGet();
                            if (call == 1) {
                                return respond(
                                        HttpStatus.OK,
                                        """
                                        {"time":"1ms","total":2,"results":[
                                          {"id":"p"}
                                        ],"next_page":"http://localhost/list?token=tok&page=2"}""");
                            }
                            return respond(
                                    HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");
                        });

        KodikApiClient client = buildClient(webClient);

        StepVerifier.create(client.listAll(KodikListRequest.builder().limit(1).build()))
                .expectNextCount(1)
                .expectError()
                .verify();
        assertThat(calls.get()).isEqualTo(2);
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
