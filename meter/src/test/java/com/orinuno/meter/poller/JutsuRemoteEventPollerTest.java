/*
 * JutsuRemoteEventPollerTest — ADR 0019 Phase 4.11 invariant.
 *
 * Locks the meter-side polling contract for orinuno-source-jutsu's
 * /api/v1/source-events/ready:
 *
 *   1. No watermark → request omits updatedSince + uses configured limit.
 *   2. Watermark present → forwarded as updatedSince=ISO_DATETIME.
 *   3. Each event forwarded to CatalogSinkEventEmitter.emit.
 *   4. Watermark advances to max Provenance.fetchedAt seen.
 *   5. Upstream 502 leaves watermark untouched + records error.
 *
 * HttpServer-backed so WebClient JSON + @JsonTypeInfo discriminator are real.
 */
package com.orinuno.meter.poller;

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

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.meter.catalog.ingestion.CatalogSinkEventEmitter;
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
@DisplayName("JutsuRemoteEventPoller — ADR 0019 Phase 4.11 ingestion contract")
class JutsuRemoteEventPollerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

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
    @DisplayName("first run: no watermark → updatedSince omitted")
    void firstRunOmitsUpdatedSince() {
        when(watermarkRepository.findBySourceType("jutsu")).thenReturn(Optional.empty());
        currentHandler.set(exchange -> sendJson(exchange, 200, "[]"));

        JutsuRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        assertThat(capturedQuery.get()).contains("limit=50").doesNotContain("updatedSince");
        verify(emitter, never()).emit(any());
        verify(watermarkRepository)
                .upsert(eq("jutsu"), isNull(), any(LocalDateTime.class), eq(0), isNull());
    }

    @Test
    @DisplayName("watermark present → forwarded as updatedSince ISO datetime")
    void subsequentRunForwardsWatermark() {
        LocalDateTime watermark = LocalDateTime.of(2026, 5, 11, 9, 30, 0);
        when(watermarkRepository.findBySourceType("jutsu"))
                .thenReturn(
                        Optional.of(
                                RemoteSourceWatermark.builder()
                                        .sourceType("jutsu")
                                        .lastFetchedAt(watermark)
                                        .build()));
        currentHandler.set(exchange -> sendJson(exchange, 200, "[]"));

        JutsuRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        assertThat(capturedQuery.get())
                .contains("limit=50")
                .contains("updatedSince=2026-05-11T09:30");
    }

    @Test
    @DisplayName("each event forwarded to emitter; watermark advances to max fetchedAt")
    void eventsAreEmittedAndWatermarkAdvances() {
        when(watermarkRepository.findBySourceType("jutsu")).thenReturn(Optional.empty());
        String body =
                "[{\"kind\":\"title-observed\",\"identifier\":{\"sourceType\":\"jutsu\",\"sourceId\":\"naruto\"},"
                    + "\"info\":{\"titleRu\":\"Наруто\",\"titleEn\":null,\"year\":2002,\"kindHint\":\"ANIME\","
                    + "\"externalIds\":{},\"posterUrl\":null,\"bigPosterUrl\":null,\"screenshotUrls\":[],\"trailerUrls\":[]},"
                    + "\"provenance\":{\"sourceUrl\":\"https://jut.su/anime/naruto/\",\"fetchedAt\":\"2026-05-12T08:00:00Z\"}},"
                    + "{\"kind\":\"title-observed\",\"identifier\":{\"sourceType\":\"jutsu\",\"sourceId\":\"bleach\"},"
                    + "\"info\":{\"titleRu\":\"Блич\",\"titleEn\":null,\"year\":2004,\"kindHint\":\"ANIME\","
                    + "\"externalIds\":{},\"posterUrl\":null,\"bigPosterUrl\":null,\"screenshotUrls\":[],\"trailerUrls\":[]},"
                    + "\"provenance\":{\"sourceUrl\":\"https://jut.su/anime/bleach/\",\"fetchedAt\":\"2026-05-12T09:15:00Z\"}}"
                    + "]";
        currentHandler.set(exchange -> sendJson(exchange, 200, body));

        JutsuRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        verify(emitter, times(2)).emit(any(SourceCatalogEvent.class));
        ArgumentCaptor<LocalDateTime> watermarkCaptor =
                ArgumentCaptor.forClass(LocalDateTime.class);
        verify(watermarkRepository)
                .upsert(
                        eq("jutsu"),
                        watermarkCaptor.capture(),
                        any(LocalDateTime.class),
                        eq(2),
                        isNull());
        assertThat(watermarkCaptor.getValue())
                .as("watermark must advance to the latest fetchedAt seen in the batch")
                .isEqualTo(LocalDateTime.of(2026, 5, 12, 9, 15, 0));
    }

    @Test
    @DisplayName("upstream 502 → watermark untouched, error recorded")
    void upstreamErrorPreservesWatermark() {
        LocalDateTime prior = LocalDateTime.of(2026, 5, 11, 9, 30, 0);
        when(watermarkRepository.findBySourceType("jutsu"))
                .thenReturn(
                        Optional.of(
                                RemoteSourceWatermark.builder()
                                        .sourceType("jutsu")
                                        .lastFetchedAt(prior)
                                        .build()));
        currentHandler.set(exchange -> sendJson(exchange, 502, "{\"error\":\"boom\"}"));

        JutsuRemoteEventPoller poller = newPoller();
        poller.pollOnce();

        verify(emitter, never()).emit(any());
        verify(watermarkRepository, atLeastOnce())
                .upsert(eq("jutsu"), eq(prior), any(LocalDateTime.class), eq(0), anyString());
    }

    private JutsuRemoteEventPoller newPoller() {
        return new JutsuRemoteEventPoller(
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
