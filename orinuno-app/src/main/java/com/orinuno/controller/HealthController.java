package com.orinuno.controller;

import com.kodik.sdk.drift.DriftRecord;
import com.orinuno.client.KodikResponseMapper;
import com.orinuno.model.KodikProxy;
import com.orinuno.model.OrinunoDumpState;
import com.orinuno.model.ParseRequestStatus;
import com.orinuno.repository.ParseRequestRepository;
import com.orinuno.service.DecoderHealthTracker;
import com.orinuno.service.ProxyProviderService;
import com.orinuno.service.decoder.DecoderPathCache;
import com.orinuno.service.dumps.KodikDumpService;
import com.orinuno.token.KodikTokenEntry;
import com.orinuno.token.KodikTokenRegistry;
import com.orinuno.token.KodikTokenTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
    private final KodikTokenRegistry kodikTokenRegistry;
    private final ParseRequestRepository parseRequestRepository;
    private final KodikDumpService kodikDumpService;
    private final DecoderPathCache decoderPathCache;

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

    @GetMapping("/dumps")
    @Operation(
            summary = "Kodik public dump endpoints (DUMP-1)",
            description =
                    "Returns the latest known state for every tracked Kodik dump"
                            + " (calendar.json / serials.json / films.json). When"
                            + " orinuno.dumps.enabled=false the snapshot is empty.")
    public ResponseEntity<Map<String, Object>> dumpsHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        List<OrinunoDumpState> snapshot = kodikDumpService.snapshot();
        long stale =
                snapshot.stream()
                        .filter(
                                s ->
                                        s.getConsecutiveFailures() != null
                                                && s.getConsecutiveFailures() > 0)
                        .count();
        body.put("status", stale > 0 ? "DEGRADED" : "OK");
        body.put("trackedDumps", snapshot.size());
        body.put("dumpsWithFailures", stale);
        body.put(
                "dumps",
                snapshot.stream()
                        .map(
                                s -> {
                                    Map<String, Object> e = new LinkedHashMap<>();
                                    e.put("name", s.getDumpName());
                                    e.put("url", s.getDumpUrl());
                                    e.put(
                                            "lastCheckedAt",
                                            s.getLastCheckedAt() == null
                                                    ? null
                                                    : s.getLastCheckedAt().toString());
                                    e.put(
                                            "lastChangedAt",
                                            s.getLastChangedAt() == null
                                                    ? null
                                                    : s.getLastChangedAt().toString());
                                    e.put("lastStatus", s.getLastStatus());
                                    e.put("lastErrorMessage", s.getLastErrorMessage());
                                    e.put("etag", s.getEtag());
                                    e.put("lastModifiedHeader", s.getLastModifiedHeader());
                                    e.put("contentLength", s.getContentLength());
                                    e.put("consecutiveFailures", s.getConsecutiveFailures());
                                    return e;
                                })
                        .toList());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/decoder/path-cache")
    @Operation(
            summary = "Per-netloc decoder path cache (DECODE-2)",
            description =
                    "Returns the in-memory snapshot of the persistent video-info POST path cache."
                            + " Hydrated from kodik_decoder_path_cache on startup; updated on every"
                            + " successful decode that resolves a fresh netloc.")
    public ResponseEntity<Map<String, Object>> decoderPathCacheHealth() {
        Map<String, String> snapshot = decoderPathCache.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "OK");
        body.put("knownNetlocs", snapshot.size());
        body.put("cache", snapshot);
        return ResponseEntity.ok(body);
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

    /**
     * Aggregated readiness probe for downstream consumers (parser-kodik first). One call instead of
     * four — see operations/parser-kodik-integration §1.
     *
     * <p>Status is the worst of the per-check verdicts:
     *
     * <ul>
     *   <li>{@code READY} — every check passes
     *   <li>{@code DEGRADED} — at least one check warns (e.g. schema drift detected)
     *   <li>{@code BLOCKED} — at least one check fails (no live tokens, queue completely stuck, …);
     *       HTTP 503 so probes flip the consumer to back-off
     * </ul>
     */
    @GetMapping("/integration")
    @Operation(
            summary = "Aggregated readiness for downstream consumers (READY/DEGRADED/BLOCKED)",
            description =
                    "Single endpoint for parser-kodik (and similar) to check before submitting"
                            + " work. Aggregates token registry, schema-drift, parse-request queue"
                            + " depth.")
    public ResponseEntity<Map<String, Object>> integrationHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service", "orinuno");

        IntegrationCheck tokens = checkTokens();
        IntegrationCheck drift = checkSchemaDrift();
        IntegrationCheck queue = checkQueueDepth();

        Map<String, Object> checks = new LinkedHashMap<>();
        checks.put("tokens", tokens.toMap());
        checks.put("schemaDrift", drift.toMap());
        checks.put("queue", queue.toMap());
        body.put("checks", checks);

        IntegrationStatus overall =
                IntegrationStatus.worst(tokens.status, drift.status, queue.status);
        body.put("status", overall.name());

        HttpStatus httpStatus =
                overall == IntegrationStatus.BLOCKED
                        ? HttpStatus.SERVICE_UNAVAILABLE
                        : HttpStatus.OK;
        return ResponseEntity.status(httpStatus).body(body);
    }

    private IntegrationCheck checkTokens() {
        Map<KodikTokenTier, List<KodikTokenEntry>> snapshot = kodikTokenRegistry.snapshot();
        int live = 0;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (KodikTokenTier tier : KodikTokenTier.values()) {
            List<KodikTokenEntry> bucket = snapshot.getOrDefault(tier, List.of());
            counts.put(tier.getJsonKey(), bucket.size());
            if (tier != KodikTokenTier.DEAD) {
                live += bucket.size();
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("liveCount", live);
        details.put("counts", counts);
        IntegrationStatus status = live == 0 ? IntegrationStatus.BLOCKED : IntegrationStatus.READY;
        String detail =
                live == 0
                        ? "No usable Kodik tokens. Seed via KODIK_TOKEN env or"
                                + " data/kodik_tokens.json."
                        : live + " token(s) live across stable/unstable/legacy tiers";
        return new IntegrationCheck(status, detail, details);
    }

    private IntegrationCheck checkSchemaDrift() {
        Map<String, DriftRecord> drifts = kodikResponseMapper.getDetectedDrifts();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalChecks", kodikResponseMapper.getTotalChecks().get());
        details.put("totalDriftsDetected", kodikResponseMapper.getTotalDriftsDetected().get());
        details.put("affectedTypes", drifts.size());
        if (drifts.isEmpty()) {
            return new IntegrationCheck(IntegrationStatus.READY, "No drift since startup", details);
        }
        return new IntegrationCheck(
                IntegrationStatus.DEGRADED,
                "Drift detected in " + drifts.size() + " type(s); see /api/v1/health/schema-drift",
                details);
    }

    private IntegrationCheck checkQueueDepth() {
        long pending = safeCount(ParseRequestStatus.PENDING);
        long running = safeCount(ParseRequestStatus.RUNNING);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("pending", pending);
        details.put("running", running);
        if (pending < 0 || running < 0) {
            return new IntegrationCheck(
                    IntegrationStatus.BLOCKED, "Queue depth unavailable (DB error)", details);
        }
        if (pending >= 1000) {
            return new IntegrationCheck(
                    IntegrationStatus.DEGRADED,
                    "PENDING queue is large (" + pending + " rows) — worker may be falling behind",
                    details);
        }
        return new IntegrationCheck(IntegrationStatus.READY, "Queue depth nominal", details);
    }

    private long safeCount(ParseRequestStatus status) {
        try {
            return parseRequestRepository.countByStatus(status);
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    private enum IntegrationStatus {
        READY,
        DEGRADED,
        BLOCKED;

        static IntegrationStatus worst(IntegrationStatus... statuses) {
            IntegrationStatus worst = READY;
            for (IntegrationStatus s : statuses) {
                if (s.ordinal() > worst.ordinal()) worst = s;
            }
            return worst;
        }
    }

    private record IntegrationCheck(
            IntegrationStatus status, String detail, Map<String, Object> details) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", status.name());
            out.put("detail", detail);
            out.put("details", details);
            return out;
        }
    }
}
