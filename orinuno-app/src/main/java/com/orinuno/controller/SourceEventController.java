package com.orinuno.controller;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.service.ExportDataService;
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

/**
 * Stage B of ARCH-0017: streams ready-for-export L1 rows as producer-side {@link
 * SourceCatalogEvent}s. Open consumers (the external aggregator's {@code external bridge}, future
 * OSS aggregators) drive incremental polling via {@code updatedSince}.
 *
 * <p>The legacy {@code /api/v1/export/ready} endpoint (proprietary {@code ContentExportDto}) stays
 * available throughout this PR for downstream consumer back-compat. It will be retired once Stage C
 * completes and the production scheduler is on the new endpoint.
 */
@RestController
@RequestMapping("/api/v1/source-events")
@RequiredArgsConstructor
@Tag(
        name = "Source Events",
        description =
                "Producer-side SourceCatalogEvent stream — orinuno's open contract for any consumer"
                        + " (external meter, OSS aggregators) to ingest source catalog state.")
public class SourceEventController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final ExportDataService exportDataService;

    @GetMapping("/ready")
    @Operation(
            summary = "List ready-for-export L1 rows as SourceCatalogEvents (ARCH-0017)",
            description =
                    "Returns Movie/SeriesDiscovered events for content with at least one decoded"
                            + " mp4 link. Falls back to TitleObserved if every variant was filtered"
                            + " out (e.g. all links expired). Use updatedSince for incremental"
                            + " polling.")
    public ResponseEntity<List<SourceCatalogEvent>> getReadyEvents(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    @Parameter(
                            description =
                                    "Only return content updated after this timestamp (ISO 8601)")
                    LocalDateTime updatedSince) {
        int effectiveLimit = clampLimit(limit);
        return ResponseEntity.ok(
                exportDataService.findReadyForExportAsEvents(updatedSince, effectiveLimit));
    }

    private static int clampLimit(int requested) {
        if (requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
