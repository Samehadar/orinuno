package com.orinuno.controller;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.kodik.client.KodikResponseMapper;
import com.kodik.drift.DriftRecord;
import com.kodik.token.KodikTokenEntry;
import com.kodik.token.KodikTokenRegistry;
import com.kodik.token.KodikTokenTier;
import com.orinuno.service.DecoderHealthTracker;
import com.orinuno.service.ProxyProviderService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

@ExtendWith(MockitoExtension.class)
class HealthControllerIntegrationEndpointTest {

    @Mock private ProxyProviderService proxyProviderService;
    @Mock private KodikResponseMapper kodikResponseMapper;
    @Mock private KodikTokenRegistry kodikTokenRegistry;
    @Mock private com.orinuno.service.dumps.KodikDumpService kodikDumpService;
    @Mock private com.orinuno.service.decoder.DecoderPathCache decoderPathCache;

    private DecoderHealthTracker decoderHealthTracker;
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        decoderHealthTracker = new DecoderHealthTracker(new SimpleMeterRegistry());
        HealthController controller =
                new HealthController(
                        decoderHealthTracker,
                        proxyProviderService,
                        kodikResponseMapper,
                        kodikTokenRegistry,
                        kodikDumpService,
                        decoderPathCache);
        client = WebTestClient.bindToController(controller).build();

        lenient().when(kodikResponseMapper.getDetectedDrifts()).thenReturn(Map.of());
        lenient().when(kodikResponseMapper.getTotalChecks()).thenReturn(new AtomicInteger(0));
        lenient()
                .when(kodikResponseMapper.getTotalDriftsDetected())
                .thenReturn(new AtomicInteger(0));
    }

    @Test
    @DisplayName("READY when tokens live and no drift")
    void readyWhenAllChecksPass() {
        primeTokens(2);

        client.get()
                .uri("/api/v1/health/integration")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("READY")
                .jsonPath("$.checks.tokens.status")
                .isEqualTo("READY")
                .jsonPath("$.checks.tokens.details.liveCount")
                .isEqualTo(2)
                .jsonPath("$.checks.queue")
                .doesNotExist();
    }

    @Test
    @DisplayName("BLOCKED + 503 when token registry empty")
    void blockedWhenNoLiveTokens() {
        primeTokens(0);

        client.get()
                .uri("/api/v1/health/integration")
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("BLOCKED")
                .jsonPath("$.checks.tokens.status")
                .isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("DEGRADED when schema-drift is detected")
    void degradedWhenDriftDetected() {
        primeTokens(1);
        when(kodikResponseMapper.getDetectedDrifts())
                .thenReturn(
                        Map.of(
                                "KodikSearchResponse",
                                new DriftRecord(
                                        Set.of("unexpected_field"),
                                        Instant.now(),
                                        Instant.now(),
                                        7)));

        client.get()
                .uri("/api/v1/health/integration")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo("DEGRADED")
                .jsonPath("$.checks.schemaDrift.status")
                .isEqualTo("DEGRADED");
    }

    private void primeTokens(int liveCount) {
        EnumMap<KodikTokenTier, List<KodikTokenEntry>> snapshot =
                new EnumMap<>(KodikTokenTier.class);
        for (KodikTokenTier tier : KodikTokenTier.values()) {
            snapshot.put(tier, List.of());
        }
        if (liveCount > 0) {
            snapshot.put(
                    KodikTokenTier.STABLE,
                    java.util.stream.IntStream.range(0, liveCount)
                            .mapToObj(i -> KodikTokenEntry.builder().value("tok-" + i).build())
                            .toList());
        }
        when(kodikTokenRegistry.snapshot()).thenReturn(snapshot);
    }
}
