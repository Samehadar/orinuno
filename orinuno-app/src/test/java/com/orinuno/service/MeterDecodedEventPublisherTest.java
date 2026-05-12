/*
 * MeterDecodedEventPublisherTest — ADR 0021 §B2-decoded.
 *
 * Locks the wire format orinuno-app pushes to meter:
 *   1. URL: POST /api/v1/source-events/decoded
 *   2. Body: single-element JSON array containing a SourceCatalogEvent.VariantDecoded
 *      with (identifier, season, episode, variantIdentifier, decodedMediaUrl,
 *      decodedQuality, decodeMethod, ttlSeconds=null, provenance).
 *   3. Missing required variant fields → publish is a silent no-op (the decoder
 *      side path must not break).
 *   4. Upstream failure does not throw — the decode itself already succeeded.
 *
 * Uses a com.sun.net HttpServer so the WebClient round-trip + Jackson sealed
 * polymorphism are exercised end-to-end.
 */
package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.kodik.decoder.DecodeAttemptResult;
import com.kodik.decoder.DecodeMethod;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.model.KodikEpisodeVariant;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

@DisplayName("MeterDecodedEventPublisher — ADR 0021 §B2-decoded wire format")
class MeterDecodedEventPublisherTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);

    private HttpServer backend;
    private AtomicReference<String> capturedPath;
    private AtomicReference<byte[]> capturedBody;
    private AtomicReference<Integer> upstreamStatus;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        capturedPath = new AtomicReference<>();
        capturedBody = new AtomicReference<>();
        upstreamStatus = new AtomicReference<>(202);
        backend.createContext(
                "/",
                exchange -> {
                    capturedPath.set(exchange.getRequestURI().getRawPath());
                    capturedBody.set(exchange.getRequestBody().readAllBytes());
                    exchange.sendResponseHeaders(upstreamStatus.get(), -1);
                    exchange.close();
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
    @DisplayName("publishDecoded POSTs to /api/v1/source-events/decoded with VariantDecoded body")
    void publishWiresVariantDecoded() throws Exception {
        MeterDecodedEventPublisher publisher = newPublisher();
        KodikEpisodeVariant variant =
                KodikEpisodeVariant.builder()
                        .id(42L)
                        .contentId(7L)
                        .seasonNumber(1)
                        .episodeNumber(3)
                        .kodikLink("https://kodik.example/iframe/7/3")
                        .build();
        DecodeAttemptResult result = sampleResult();

        publisher.publishDecoded(variant, result, "720", "https://cdn.kodik.example/decoded.mp4");

        waitFor(() -> capturedBody.get() != null);

        assertThat(capturedPath.get()).isEqualTo("/api/v1/source-events/decoded");
        List<SourceCatalogEvent> body =
                MAPPER.readerForListOf(SourceCatalogEvent.class)
                        .readValue(new String(capturedBody.get(), StandardCharsets.UTF_8));
        assertThat(body).hasSize(1).first().isInstanceOf(SourceCatalogEvent.VariantDecoded.class);
        SourceCatalogEvent.VariantDecoded vd = (SourceCatalogEvent.VariantDecoded) body.get(0);
        assertThat(vd.identifier().sourceType()).isEqualTo("kodik");
        assertThat(vd.identifier().sourceId()).isEqualTo("7");
        assertThat(vd.season()).isEqualTo(1);
        assertThat(vd.episode()).isEqualTo(3);
        assertThat(vd.variantIdentifier().sourceId()).isEqualTo("42");
        assertThat(vd.decodedMediaUrl()).isEqualTo("https://cdn.kodik.example/decoded.mp4");
        assertThat(vd.decodedQuality()).isEqualTo("720");
        assertThat(vd.ttlSeconds()).isNull();
        assertThat(vd.provenance().sourceUrl()).isEqualTo("https://kodik.example/iframe/7/3");
    }

    @Test
    @DisplayName("publishDecoded with null required field → silent no-op (no POST fired)")
    void publishSkipsOnMissingFields() {
        MeterDecodedEventPublisher publisher = newPublisher();

        // contentId missing — must not even attempt a POST.
        publisher.publishDecoded(
                KodikEpisodeVariant.builder()
                        .id(42L)
                        .seasonNumber(1)
                        .episodeNumber(3)
                        .build(),
                sampleResult(),
                "720",
                "https://x");

        // Give any (hypothetical) async POST a chance to land.
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(capturedBody.get()).isNull();
    }

    @Test
    @DisplayName("upstream 5xx is swallowed — caller path keeps running")
    void upstreamFailureDoesNotThrow() {
        upstreamStatus.set(503);
        MeterDecodedEventPublisher publisher = newPublisher();
        KodikEpisodeVariant variant =
                KodikEpisodeVariant.builder()
                        .id(1L)
                        .contentId(2L)
                        .seasonNumber(1)
                        .episodeNumber(1)
                        .kodikLink("https://kodik.example/iframe/2/1")
                        .build();

        // Must not throw despite the 503 — fire-and-forget contract.
        publisher.publishDecoded(variant, sampleResult(), "480", "https://x.mp4");

        waitFor(() -> capturedBody.get() != null);
    }

    private MeterDecodedEventPublisher newPublisher() {
        return new MeterDecodedEventPublisher(WebClient.builder(), baseUrl, CLOCK);
    }

    private static DecodeAttemptResult sampleResult() {
        return new DecodeAttemptResult(DecodeMethod.REGEX, java.util.Map.of("720", "https://x"));
    }

    private static void waitFor(java.util.function.BooleanSupplier predicate) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (predicate.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("predicate never became true within 3s");
    }

}
