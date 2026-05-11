/*
 * KodikRemoteEventPollerTest — ADR 0018 Phase 2.11 invariant.
 *
 * Locks the orinuno-side polling contract for orinuno-source-kodik's
 * /api/v1/source-events/ready endpoint:
 *
 *   1. With no stored watermark, the poller calls the endpoint without
 *      updatedSince and uses the configured batch size as limit.
 *   2. With a watermark, the poller forwards updatedSince=ISO_DATETIME.
 *   3. Each event in the response is forwarded to CatalogSinkEventEmitter.emit.
 *   4. The watermark is advanced to the maximum Provenance.fetchedAt seen.
 *   5. Upstream errors (5xx / network / parse) leave the watermark untouched
 *      and record the failure on the row instead — next tick retries.
 *
 * Backed by a tiny java.net HttpServer so the test exercises the real
 * WebClient JSON parser + Spring's @JsonTypeInfo discriminator without a
 * Spring context. Matches the style used in KodikUpstreamProxyFilterTest.
 */
package com.orinuno.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.catalog.ingestion.CatalogSinkEventEmitter;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.model.RemoteSourceWatermark;
import com.orinuno.repository.RemoteSourceWatermarkRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

@ExtendWith(MockitoExtension.class)
@DisplayName("KodikRemoteEventPoller — ADR 0018 Phase 2.11 ingestion contract")
class KodikRemoteEventPollerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-11T10:00:00Z"), ZoneOffset.UTC);

    @Mock private RemoteSourceWatermarkRepository watermarkRepository;
    @Mock private CatalogSinkEventEmitter emitter;

    private HttpServer backend;
    private AtomicReference<HttpHandler> currentHandler;
    private AtomicReference<String> capturedQuery;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        currentHandler = new AtomicReference<>();
        capturedQuery = new AtomicReference<>();
        backend.createContext(
                "/api/v1/source-events/ready",
                exchange -> {
                    capturedQuery.set(exchange.getRequestURI().getRawQuery());
                    currentHandler.get().handle(exchange);
                });
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
    @DisplayName("first run: no watermark → request omits updatedSince and uses configured limit")
    void firstRunOmitsUpdatedSince() {
        when(watermarkRepository.findBySourceType("kodik")).thenReturn(Optional.empty());
        currentHandler.set(exchange -> sendJson(exchange, 200, "[]"));

        KodikRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        assertThat(capturedQuery.get()).contains("limit=50").doesNotContain("updatedSince");
        verify(emitter, never()).emit(any());
        verify(watermarkRepository)
                .upsert(eq("kodik"), isNull(), any(LocalDateTime.class), eq(0), isNull());
    }

    @Test
    @DisplayName("watermark present → forwarded as updatedSince ISO datetime")
    void subsequentRunForwardsWatermark() {
        LocalDateTime watermark = LocalDateTime.of(2026, 5, 10, 9, 30, 0);
        when(watermarkRepository.findBySourceType("kodik"))
                .thenReturn(
                        Optional.of(
                                RemoteSourceWatermark.builder()
                                        .sourceType("kodik")
                                        .lastFetchedAt(watermark)
                                        .build()));
        currentHandler.set(exchange -> sendJson(exchange, 200, "[]"));

        KodikRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        assertThat(capturedQuery.get())
                .contains("limit=50")
                .contains("updatedSince=2026-05-10T09:30");
    }

    @Test
    @DisplayName("each event is forwarded to the emitter; watermark advances to max fetchedAt")
    void eventsAreEmittedAndWatermarkAdvances() {
        when(watermarkRepository.findBySourceType("kodik")).thenReturn(Optional.empty());
        String body =
                "[{\"kind\":\"title-observed\",\"identifier\":{\"sourceType\":\"kodik\",\"sourceId\":\"100\"},"
                    + "\"info\":{\"titleRu\":\"A\",\"titleEn\":null,\"year\":null,\"kindHint\":\"UNKNOWN\","
                    + "\"externalIds\":{},\"posterUrl\":null,\"bigPosterUrl\":null,\"screenshotUrls\":[],\"trailerUrls\":[]},"
                    + "\"provenance\":{\"sourceUrl\":\"orinuno-app://kodik/100\",\"fetchedAt\":\"2026-05-11T08:00:00Z\"}},"
                    + "{\"kind\":\"title-observed\",\"identifier\":{\"sourceType\":\"kodik\",\"sourceId\":\"101\"},"
                    + "\"info\":{\"titleRu\":\"B\",\"titleEn\":null,\"year\":null,\"kindHint\":\"UNKNOWN\","
                    + "\"externalIds\":{},\"posterUrl\":null,\"bigPosterUrl\":null,\"screenshotUrls\":[],\"trailerUrls\":[]},"
                    + "\"provenance\":{\"sourceUrl\":\"orinuno-app://kodik/101\",\"fetchedAt\":\"2026-05-11T09:15:00Z\"}}"
                    + "]";
        currentHandler.set(exchange -> sendJson(exchange, 200, body));

        KodikRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        verify(emitter, times(2)).emit(any(SourceCatalogEvent.class));
        ArgumentCaptor<LocalDateTime> watermarkCaptor =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(watermarkRepository)
                .upsert(
                        eq("kodik"),
                        watermarkCaptor.capture(),
                        any(LocalDateTime.class),
                        eq(2),
                        isNull());
        assertThat(watermarkCaptor.getValue())
                .as("watermark must advance to the latest fetchedAt seen in the batch")
                .isEqualTo(LocalDateTime.of(2026, 5, 11, 9, 15, 0));
    }

    @Test
    @DisplayName("upstream 502 → watermark untouched, error recorded for ops visibility")
    void upstreamErrorPreservesWatermark() {
        LocalDateTime prior = LocalDateTime.of(2026, 5, 10, 9, 30, 0);
        when(watermarkRepository.findBySourceType("kodik"))
                .thenReturn(
                        Optional.of(
                                RemoteSourceWatermark.builder()
                                        .sourceType("kodik")
                                        .lastFetchedAt(prior)
                                        .build()));
        currentHandler.set(exchange -> sendJson(exchange, 502, "{\"error\":\"boom\"}"));

        KodikRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        verify(emitter, never()).emit(any());
        verify(watermarkRepository, atLeastOnce())
                .upsert(eq("kodik"), eq(prior), any(LocalDateTime.class), eq(0), anyString());
    }

    private KodikRemoteEventPoller newPoller() {
        return new KodikRemoteEventPoller(
                WebClient.builder(), baseUrl, 50, watermarkRepository, emitter, FIXED_CLOCK);
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
}
