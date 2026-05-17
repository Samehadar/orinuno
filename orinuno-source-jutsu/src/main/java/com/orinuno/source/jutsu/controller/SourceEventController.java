/*
 * SourceEventController — ADR 0019 Phase 4.6.
 *
 * Producer-side event stream of this service's L1 jut.su catalog. The OSS meter
 * aggregator (Phase 4.11) and any out-of-tree downstream adapter poll this endpoint
 * for SourceCatalogEvent payloads. Wire shape identical to orinuno-source-kodik's
 * stream — sealed event hierarchy from orinuno-source-contract (ADR 0017).
 */
package com.orinuno.source.jutsu.controller;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.source.jutsu.service.JutsuSourceEventProjection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/source-events")
@RequiredArgsConstructor
@Tag(
        name = "Source Events",
        description =
                "Producer-side SourceCatalogEvent stream — open contract for any downstream"
                        + " consumer ( meter, OSS meter, OSS aggregators) to ingest jut.su catalog"
                        + " state.")
public class SourceEventController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final JutsuSourceEventProjection projection;

    @GetMapping("/ready")
    @Operation(
            summary = "List ready jut.su L1 rows as SourceCatalogEvents",
            description =
                    "Returns MovieDiscovered events for film-only titles, SeriesDiscovered for"
                            + " titles with at least one episode, TitleObserved when neither"
                            + " applies. Use updatedSince for incremental polling.")
    public ResponseEntity<List<SourceCatalogEvent>> getReadyEvents(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    @Parameter(
                            description =
                                    "Only return titles with last_seen_at at/after this timestamp"
                                            + " (ISO 8601)")
                    LocalDateTime updatedSince) {
        return ResponseEntity.ok(projection.findReadyEvents(updatedSince, clampLimit(limit)));
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
