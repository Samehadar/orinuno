/*
 * SourceEventController — ADR 0018 Phase 2.6.
 *
 * Producer-side event stream of this service's L1 Kodik catalog. The OSS aggregator and any out-of-tree
 * external bridge and the future OSS meter aggregator poll this endpoint for
 * SourceCatalogEvent payloads and decide what to do with them. The wire-format JSON
 * shape is identical to what orinuno-app emits today (mirrors ADR 0017's
 * orinuno-source-contract) so existing consumers swap endpoints without code change.
 */
package com.orinuno.source.kodik.controller;

import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.source.kodik.service.KodikSourceEventProjection;
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
 * {@code GET /api/v1/source-events/ready} — incremental Kodik event stream.
 *
 * <p>Producer-side contract from {@code orinuno-source-contract} (ADR 0017): consumers read {@code
 * SourceCatalogEvent} payloads (sealed: {@code TitleObserved} / {@code MovieDiscovered} / {@code
 * SeriesDiscovered}) and reconcile them into their own canonical catalog. {@code updatedSince} is
 * the watermark — pass the {@code Provenance.fetchedAt} of the latest event you processed; the
 * response is the next batch.
 *
 * <p>Per ADR 0018 Phase 2.6, each variant's {@link
 * com.orinuno.contract.source.SourceEpisodeVariant#mediaUrl()} carries the long-lived Kodik {@code
 * kodikLink} iframe URL — not a decoded mp4 URL. Decoding is deferred to the consumer (TECH_DEBT
 * ARCH-0018, JIT decode trajectory). The iframe URL is stable across days, so the event payload is
 * durable; the mp4 URL would have been stale by the time most consumers read it anyway.
 */
@RestController
@RequestMapping("/api/v1/source-events")
@RequiredArgsConstructor
@Tag(
        name = "Source Events",
        description =
                "Producer-side SourceCatalogEvent stream — open contract for any downstream"
                        + " consumer ( meter, OSS aggregators) to ingest Kodik catalog state.")
public class SourceEventController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 200;

    private final KodikSourceEventProjection projection;

    @GetMapping("/ready")
    @Operation(
            summary = "List ready Kodik L1 rows as SourceCatalogEvents",
            description =
                    "Returns Movie/SeriesDiscovered events for content with at least one iframe"
                            + " variant. Falls back to TitleObserved if every variant lacks a"
                            + " kodikLink. Use updatedSince for incremental polling.")
    public ResponseEntity<List<SourceCatalogEvent>> getReadyEvents(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    @Parameter(
                            description =
                                    "Only return content updated after this timestamp (ISO 8601)")
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
