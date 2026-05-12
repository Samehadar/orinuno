/*
 * ExportController — ADR 0021 §C4.1.
 *
 * Read-only export of denormalised Kodik content. Ported field-for-field
 * from orinuno-app's ExportController so the demo UI keeps working
 * unchanged after C4.2 adds /api/v1/export/ to KodikUpstreamProxyFilter.
 */
package com.orinuno.source.kodik.controller;

import com.orinuno.source.kodik.model.dto.ContentExportDto;
import com.orinuno.source.kodik.model.dto.PageRequest;
import com.orinuno.source.kodik.model.dto.PageResponse;
import com.orinuno.source.kodik.service.ContentExportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/export")
@RequiredArgsConstructor
@Tag(name = "Export", description = "Export parsed Kodik content for external consumers")
public class ExportController {

    private final ContentExportService exportService;

    @GetMapping("/{contentId}")
    @Operation(summary = "Get full export package for specific content")
    public Mono<ResponseEntity<ContentExportDto>> getExportData(@PathVariable Long contentId) {
        return Mono.fromCallable(() -> exportService.getExportData(contentId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(opt -> opt.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()));
    }

    @GetMapping("/ready")
    @Operation(summary = "List content ready for export (with decoded mp4 links)")
    public Mono<ResponseEntity<PageResponse<ContentExportDto>>> getReadyForExport(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    @Parameter(
                            description =
                                    "Only return content updated after this timestamp (ISO 8601)")
                    LocalDateTime updatedSince) {
        PageRequest pageRequest = PageRequest.builder().page(page).size(size).build();
        return Mono.fromCallable(() -> exportService.getReadyForExport(pageRequest, updatedSince))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }
}
