package com.orinuno.controller;

import com.orinuno.client.KodikResponseMapper;
import com.orinuno.model.KodikProxy;
import com.orinuno.service.DecoderHealthTracker;
import com.orinuno.service.ProxyProviderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Health", description = "Service health and diagnostics")
public class HealthController {

    private final DecoderHealthTracker decoderHealthTracker;
    private final ProxyProviderService proxyProviderService;
    private final KodikResponseMapper kodikResponseMapper;

    @GetMapping
    @Operation(summary = "General health check")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "orinuno");
        return ResponseEntity.ok(status);
    }

    @GetMapping("/decoder")
    @Operation(summary = "Decoder health and statistics (PF6)")
    public ResponseEntity<Map<String, Object>> decoderHealth() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("successRate", decoderHealthTracker.getSuccessRate());
        status.put("totalDecoded", decoderHealthTracker.getTotalDecoded().get());
        status.put("totalFailed", decoderHealthTracker.getTotalFailed().get());
        status.put("lastFailureStep", decoderHealthTracker.getLastFailureStep().get());
        status.put("lastFailureMessage", decoderHealthTracker.getLastFailureMessage().get());
        status.put("lastFailureTime", decoderHealthTracker.getLastFailureTime().get());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/schema-drift")
    @Operation(summary = "Kodik API schema drift detection status")
    public ResponseEntity<Map<String, Object>> schemaDrift() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, KodikResponseMapper.DriftRecord> drifts =
                kodikResponseMapper.getDetectedDrifts();
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

    @GetMapping("/proxy")
    @Operation(summary = "Proxy pool statistics")
    public ResponseEntity<Map<String, Object>> proxyHealth() {
        List<KodikProxy> activeProxies = proxyProviderService.getActiveProxies();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("activeProxies", activeProxies.size());
        status.put(
                "proxies",
                activeProxies.stream()
                        .map(
                                p ->
                                        Map.of(
                                                "id", p.getId(),
                                                "host", p.getHost(),
                                                "port", p.getPort(),
                                                "type", p.getProxyType().name(),
                                                "failCount", p.getFailCount()))
                        .toList());
        return ResponseEntity.ok(status);
    }
}
