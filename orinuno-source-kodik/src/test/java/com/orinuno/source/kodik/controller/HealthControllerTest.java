/*
 * HealthControllerTest — ADR 0018 Phase 2.4d invariant.
 *
 * Locks the slim health surface this service exposes to operators / downstream
 * consumers:
 *
 *   1. /api/v1/health returns UP + service="orinuno-source-kodik" so callers
 *      can distinguish source-kodik from orinuno-app in their probes.
 *   2. /api/v1/health/tokens surfaces token-tier counts and never leaks the
 *      raw token value (always masked).
 *   3. /api/v1/health/schema-drift reflects the KodikResponseMapper drift
 *      registry — CLEAN when empty, DRIFT_DETECTED when not.
 *
 * Pure unit test — no Spring context. Mocks the four collaborators the
 * controller depends on and asserts on the returned Map shape directly.
 */
package com.orinuno.source.kodik.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.kodik.client.KodikResponseMapper;
import com.kodik.drift.DriftRecord;
import com.kodik.token.KodikTokenEntry;
import com.kodik.token.KodikTokenRegistry;
import com.kodik.token.KodikTokenTier;
import com.orinuno.source.kodik.service.DecoderHealthTracker;
import com.orinuno.source.kodik.service.ProxyProviderService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthController — ADR 0018 Phase 2.4d slim health surface")
class HealthControllerTest {

    @Mock private KodikTokenRegistry kodikTokenRegistry;
    @Mock private KodikResponseMapper kodikResponseMapper;
    @Mock private DecoderHealthTracker decoderHealthTracker;
    @Mock private ProxyProviderService proxyProviderService;

    @Test
    @DisplayName("/api/v1/health returns UP + service name = orinuno-source-kodik")
    void healthReportsServiceName() {
        HealthController controller =
                new HealthController(
                        kodikTokenRegistry,
                        kodikResponseMapper,
                        decoderHealthTracker,
                        proxyProviderService);

        ResponseEntity<Map<String, Object>> response = controller.health();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("status", "UP")
                .containsEntry("service", "orinuno-source-kodik");
    }

    @Test
    @DisplayName("/tokens reports live count, masks raw token value, never leaks the secret")
    void tokensSurfaceIsMasked() {
        KodikTokenEntry live =
                KodikTokenEntry.builder()
                        .value("abcdef0123456789abcdef0123456789")
                        .lastChecked(Instant.parse("2026-05-11T10:00:00Z"))
                        .note("seeded")
                        .build();
        when(kodikTokenRegistry.snapshot())
                .thenReturn(Map.of(KodikTokenTier.STABLE, List.of(live)));

        HealthController controller =
                new HealthController(
                        kodikTokenRegistry,
                        kodikResponseMapper,
                        decoderHealthTracker,
                        proxyProviderService);
        Map<String, Object> body = controller.tokensHealth().getBody();

        assertThat(body).containsEntry("status", "OK").containsEntry("liveCount", 1);
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> tiers =
                (Map<String, List<Map<String, Object>>>) body.get("tiers");
        List<Map<String, Object>> stable = tiers.get("stable");
        assertThat(stable).hasSize(1);
        String maskedValue = (String) stable.get(0).get("value");
        assertThat(maskedValue)
                .as("token value must be masked, never the raw secret")
                .isNotEqualTo("abcdef0123456789abcdef0123456789")
                .startsWith("abcd")
                .contains("(32ch)");
    }

    @Test
    @DisplayName("/schema-drift returns CLEAN when registry is empty")
    void schemaDriftReportsCleanWhenEmpty() {
        when(kodikResponseMapper.getDetectedDrifts()).thenReturn(Map.of());
        when(kodikResponseMapper.getTotalChecks()).thenReturn(new AtomicInteger(42));
        when(kodikResponseMapper.getTotalDriftsDetected()).thenReturn(new AtomicInteger(0));

        HealthController controller =
                new HealthController(
                        kodikTokenRegistry,
                        kodikResponseMapper,
                        decoderHealthTracker,
                        proxyProviderService);
        Map<String, Object> body = controller.schemaDrift().getBody();

        assertThat(body)
                .containsEntry("status", "CLEAN")
                .containsEntry("totalChecks", 42)
                .containsEntry("totalDriftsDetected", 0)
                .containsEntry("affectedTypes", 0);
    }

    @Test
    @DisplayName("/schema-drift escalates to DRIFT_DETECTED when registry has entries")
    void schemaDriftReportsDrift() {
        DriftRecord drift =
                new DriftRecord(
                        java.util.Set.of("brand_new_field"),
                        Instant.parse("2026-05-11T08:00:00Z"),
                        Instant.parse("2026-05-11T09:30:00Z"),
                        17);
        when(kodikResponseMapper.getDetectedDrifts()).thenReturn(Map.of("MaterialData", drift));
        when(kodikResponseMapper.getTotalChecks()).thenReturn(new AtomicInteger(100));
        when(kodikResponseMapper.getTotalDriftsDetected()).thenReturn(new AtomicInteger(17));

        HealthController controller =
                new HealthController(
                        kodikTokenRegistry,
                        kodikResponseMapper,
                        decoderHealthTracker,
                        proxyProviderService);
        Map<String, Object> body = controller.schemaDrift().getBody();

        assertThat(body)
                .containsEntry("status", "DRIFT_DETECTED")
                .containsEntry("affectedTypes", 1);
    }
}
