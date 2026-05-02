package com.orinuno.controller;

import com.orinuno.service.HlsManifestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/v1/hls")
@RequiredArgsConstructor
@Tag(name = "HLS", description = "HLS manifest retrieval for streaming integration")
public class HlsController {

    private final HlsManifestService hlsManifestService;

    @GetMapping("/{variantId}/url")
    @Operation(summary = "Get fresh m3u8 URL for a variant (requires fresh decode)")
    public Mono<ResponseEntity<Map<String, String>>> getHlsUrl(@PathVariable Long variantId) {
        log.info("HLS URL request for variant_id={}", variantId);
        return hlsManifestService
                .getHlsUrl(variantId)
                .map(result -> ResponseEntity.ok(Map.of("url", result.url())))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "HLS URL failed for variant_id={}: {}",
                                    variantId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.badRequest()
                                            .body(Map.of("error", e.getMessage())));
                        });
    }

    @GetMapping(value = "/{variantId}/manifest", produces = "application/vnd.apple.mpegurl")
    @Operation(
            summary =
                    "Get absolutized m3u8 manifest for a variant (fresh decode + download +"
                            + " absolutize)")
    public Mono<ResponseEntity<String>> getAbsolutizedManifest(@PathVariable Long variantId) {
        log.info("HLS manifest request for variant_id={}", variantId);
        return hlsManifestService
                .getAbsolutizedManifest(variantId)
                .map(
                        result ->
                                ResponseEntity.ok()
                                        .contentType(
                                                MediaType.parseMediaType(
                                                        "application/vnd.apple.mpegurl"))
                                        .body(result.manifest()))
                .onErrorResume(
                        e -> {
                            log.error(
                                    "HLS manifest failed for variant_id={}: {}",
                                    variantId,
                                    e.getMessage());
                            return Mono.just(
                                    ResponseEntity.badRequest()
                                            .contentType(MediaType.TEXT_PLAIN)
                                            .body("Error: " + e.getMessage()));
                        });
    }
}
