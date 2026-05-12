/*
 * HealthController — slim post-D3 surface.
 *
 * After ADR 0021 §D3 the decoder + dumps + Kodik token + proxy stacks
 * all moved to source-kodik. orinuno-app keeps only a service-level
 * liveness probe here; per-source diagnostics live on the per-source
 * services (orinuno-source-kodik exposes its own /api/v1/health/{decoder,
 * tokens, schema-drift, dumps, proxy} variants).
 */
package com.orinuno.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health", description = "orinuno-app liveness probe")
public class HealthController {

    @GetMapping
    @Operation(summary = "Liveness probe")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "orinuno");
        return ResponseEntity.ok(status);
    }
}
