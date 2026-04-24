package com.orinuno.controller;

import com.orinuno.service.VideoDownloadService;
import com.orinuno.service.VideoDownloadService.DownloadState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/download")
@RequiredArgsConstructor
@Tag(name = "Download", description = "Video download to local storage")
public class DownloadController {

    private final VideoDownloadService downloadService;

    @PostMapping("/{variantId}")
    @Operation(
            summary =
                    "Start downloading a single variant (returns immediately, poll status via GET)")
    public ResponseEntity<DownloadState> downloadVariant(@PathVariable Long variantId) {
        return ResponseEntity.accepted().body(downloadService.startDownloadAsync(variantId));
    }

    @GetMapping("/{variantId}/status")
    @Operation(summary = "Check download status for a variant")
    public ResponseEntity<DownloadState> getStatus(@PathVariable Long variantId) {
        return ResponseEntity.ok(downloadService.getDownloadState(variantId));
    }

    @PostMapping("/content/{contentId}")
    @Operation(summary = "Download all undecoded variants for a content item")
    public Mono<ResponseEntity<Map<String, Object>>> downloadContent(@PathVariable Long contentId) {
        return downloadService
                .downloadAllForContent(contentId)
                .map(
                        count ->
                                ResponseEntity.ok(
                                        Map.of(
                                                "contentId", contentId,
                                                "downloadedCount", count)));
    }
}
