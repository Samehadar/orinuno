package com.orinuno.client.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kodik.sdk.drift.DriftDetector;
import com.kodik.sdk.drift.DriftSamplingProperties;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.service.metrics.KodikCalendarMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class KodikCalendarHttpClientTest {

    private static final String SAMPLE_PAYLOAD =
            """
            [
              {"next_episode": 1, "next_episode_at": "2026-05-01T10:00:00+03:00",
               "anime": {"id": "10", "name": "x", "russian": "x", "image": {}},
               "kind": "tv", "status": "ongoing", "score": 7.5,
               "episodes": 12, "episodes_aired": 3,
               "aired_on": "2026-04-01", "released_on": null}
            ]
            """;

    private HttpServer server;
    private OrinunoProperties properties;
    private SimpleMeterRegistry registry;
    private KodikCalendarMetrics metrics;
    private DriftDetector driftDetector;
    private ObjectMapper mapper;
    private AtomicReference<HandlerScript> script;
    private AtomicInteger requestCount;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        script = new AtomicReference<>(HandlerScript.OK);
        requestCount = new AtomicInteger();
        server.createContext("/calendar.json", new ScriptedHandler(script, requestCount));
        server.start();

        properties = new OrinunoProperties();
        properties.getCalendar().setUrl(baseUrl() + "/calendar.json");
        properties.getCalendar().setRequestTimeoutSeconds(2);

        registry = new SimpleMeterRegistry();
        metrics = new KodikCalendarMetrics(registry);
        driftDetector = new DriftDetector(DriftSamplingProperties.defaults());
        mapper =
                new ObjectMapper()
                        .registerModule(new JavaTimeModule())
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("Successful fetch parses entries and increments success counter")
    void successFetch() {
        KodikCalendarHttpClient client = newClient();

        StepVerifier.create(client.fetch())
                .assertNext(
                        result -> {
                            assertThat(result.entries()).hasSize(1);
                            assertThat(result.entries().get(0).anime().id()).isEqualTo("10");
                            assertThat(result.etag()).isEqualTo("\"v1\"");
                            assertThat(result.lastModified()).isNotBlank();
                        })
                .verifyComplete();

        assertThat(counter("success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Conditional GET reuses cached payload on 304 Not Modified")
    void conditionalGetReusesCache() {
        KodikCalendarHttpClient client = newClient();

        StepVerifier.create(client.fetch()).expectNextCount(1).verifyComplete();
        script.set(HandlerScript.NOT_MODIFIED);

        StepVerifier.create(client.fetch())
                .assertNext(
                        result -> {
                            assertThat(result.entries()).hasSize(1);
                            assertThat(result.etag()).isEqualTo("\"v1\"");
                        })
                .verifyComplete();

        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(counter("success")).isEqualTo(1.0);
        assertThat(counter("not_modified")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Upstream 5xx propagates as error and increments error counter")
    void upstreamError() {
        KodikCalendarHttpClient client = newClient();
        script.set(HandlerScript.SERVER_ERROR);

        StepVerifier.create(client.fetch()).expectError().verify();

        assertThat(counter("error")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Disabled fetcher errors out without making a request")
    void disabledShortCircuits() {
        properties.getCalendar().setEnabled(false);
        KodikCalendarHttpClient client = newClient();

        StepVerifier.create(client.fetch()).expectError(IllegalStateException.class).verify();

        assertThat(requestCount.get()).isZero();
        assertThat(counter("disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Oversize body is rejected before parsing")
    void oversizeRejected() {
        properties.getCalendar().setMaxResponseBytes(8);
        KodikCalendarHttpClient client = newClient();

        StepVerifier.create(client.fetch()).expectError().verify();

        assertThat(counter("error")).isEqualTo(1.0);
    }

    private KodikCalendarHttpClient newClient() {
        return new KodikCalendarHttpClient(properties, mapper, driftDetector, metrics);
    }

    private double counter(String outcome) {
        var meter =
                registry.find("orinuno.kodik.calendar.fetch")
                        .tags(Tags.of("outcome", outcome))
                        .counter();
        return meter == null ? 0.0 : meter.count();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private enum HandlerScript {
        OK,
        NOT_MODIFIED,
        SERVER_ERROR
    }

    private static final class ScriptedHandler implements HttpHandler {
        private final AtomicReference<HandlerScript> script;
        private final AtomicInteger counter;

        ScriptedHandler(AtomicReference<HandlerScript> script, AtomicInteger counter) {
            this.script = script;
            this.counter = counter;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            counter.incrementAndGet();
            try (exchange) {
                switch (script.get()) {
                    case OK -> {
                        byte[] body = SAMPLE_PAYLOAD.getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().add("ETag", "\"v1\"");
                        exchange.getResponseHeaders()
                                .add("Last-Modified", "Mon, 27 Apr 2026 00:00:00 GMT");
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(body);
                        }
                    }
                    case NOT_MODIFIED -> {
                        exchange.getResponseHeaders().add("ETag", "\"v1\"");
                        exchange.sendResponseHeaders(304, -1);
                    }
                    case SERVER_ERROR -> exchange.sendResponseHeaders(503, -1);
                }
            }
        }
    }
}
