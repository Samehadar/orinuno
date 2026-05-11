/*
 * HealthController — ADR 0018 Phase 2.4d.
 *
 * Slim mirror of orinuno-app's HealthController focused on the diagnostics
 * that are meaningful in the standalone source-kodik service: Kodik token
 * registry state and KodikResponseMapper schema-drift snapshot. Decoder /
 * dump / proxy / parse-queue checks stay in orinuno-app for now — their
 * dependencies haven't moved into this service yet (Phases 2.5 + 5).
 *
 * Spring Boot Actuator's /actuator/health is also exposed (port 8087) for
 * standard readiness probes; this controller adds the Kodik-specific checks
 * a kodik-parser operator needs when troubleshooting a standalone deploy.
 */
package com.orinuno.source.kodik.controller;

import com.kodik.client.KodikResponseMapper;
import com.kodik.drift.DriftRecord;
import com.kodik.token.KodikTokenEntry;
import com.kodik.token.KodikTokenRegistry;
import com.kodik.token.KodikTokenTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@RequiredArgsConstructor
@Tag(name = "Health", description = "orinuno-source-kodik service health and Kodik diagnostics")
public class HealthController {

    private final KodikTokenRegistry kodikTokenRegistry;
    private final KodikResponseMapper kodikResponseMapper;

    @GetMapping
    @Operation(summary = "General health check")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "orinuno-source-kodik");
        return ResponseEntity.ok(status);
    }

    @GetMapping("/tokens")
    @Operation(summary = "Kodik token registry status (masked)")
    public ResponseEntity<Map<String, Object>> tokensHealth() {
        Map<KodikTokenTier, List<KodikTokenEntry>> snapshot = kodikTokenRegistry.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> details = new LinkedHashMap<>();
        int live = 0;
        for (KodikTokenTier tier : KodikTokenTier.values()) {
            List<KodikTokenEntry> bucket = snapshot.getOrDefault(tier, List.of());
            counts.put(tier.getJsonKey(), bucket.size());
            if (tier != KodikTokenTier.DEAD) {
                live += bucket.size();
            }
            List<Map<String, Object>> tierDetails = new ArrayList<>();
            for (KodikTokenEntry entry : bucket) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("value", KodikTokenRegistry.mask(entry.getValue()));
                e.put(
                        "lastChecked",
                        entry.getLastChecked() == null ? null : entry.getLastChecked().toString());
                e.put("note", entry.getNote());
                e.put("functionsAvailability", entry.getFunctionsAvailability());
                tierDetails.add(e);
            }
            details.put(tier.getJsonKey(), tierDetails);
        }
        result.put("status", live > 0 ? "OK" : "EMPTY");
        result.put("liveCount", live);
        result.put("counts", counts);
        result.put("tiers", details);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/schema-drift")
    @Operation(summary = "Kodik API schema-drift detection status")
    public ResponseEntity<Map<String, Object>> schemaDrift() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, DriftRecord> drifts = kodikResponseMapper.getDetectedDrifts();
        boolean hasDrifts = !drifts.isEmpty();
        result.put("status", hasDrifts ? "DRIFT_DETECTED" : "CLEAN");
        result.put("totalChecks", kodikResponseMapper.getTotalChecks().get());
        result.put("totalDriftsDetected", kodikResponseMapper.getTotalDriftsDetected().get());
        result.put("affectedTypes", drifts.size());
        result.put(
                "drifts",
                drifts.entrySet().stream()
                        .map(
                                e -> {
                                    Map<String, Object> entry = new LinkedHashMap<>();
                                    entry.put("type", e.getKey());
                                    entry.put("unknownFields", e.getValue().unknownFields());
                                    entry.put("firstSeen", e.getValue().firstSeen().toString());
                                    entry.put("lastSeen", e.getValue().lastSeen().toString());
                                    entry.put("hitCount", e.getValue().hitCount());
                                    return entry;
                                })
                        .collect(Collectors.toList()));
        return ResponseEntity.ok(result);
    }
}
