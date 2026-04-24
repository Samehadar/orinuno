package com.orinuno.controller;

import com.orinuno.model.dto.ContentExportDto;
import com.orinuno.model.dto.PageRequest;
import com.orinuno.model.dto.PageResponse;
import com.orinuno.service.ExportDataService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/export")
@RequiredArgsConstructor
@Tag(name = "Export", description = "Export parsed content data for external consumers")
public class ExportController {

    private final ExportDataService exportDataService;

    @GetMapping("/{contentId}")
    @Operation(summary = "Get full export package for specific content")
    public ResponseEntity<ContentExportDto> getExportData(@PathVariable Long contentId) {
        return exportDataService
                .getExportData(contentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/ready")
    @Operation(summary = "List content ready for export (with decoded mp4 links)")
    public ResponseEntity<PageResponse<ContentExportDto>> getReadyForExport(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    @Parameter(
                            description =
                                    "Only return content updated after this timestamp (ISO 8601)")
                    LocalDateTime updatedSince) {
        PageRequest pageRequest = PageRequest.builder().page(page).size(size).build();
        return ResponseEntity.ok(exportDataService.getReadyForExport(pageRequest, updatedSince));
    }
}
