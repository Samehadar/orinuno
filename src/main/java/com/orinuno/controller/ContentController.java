package com.orinuno.controller;

import com.orinuno.model.dto.ContentDto;
import com.orinuno.model.dto.EpisodeVariantDto;
import com.orinuno.model.dto.PageRequest;
import com.orinuno.model.dto.PageResponse;
import com.orinuno.service.ContentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/content")
@RequiredArgsConstructor
@Tag(name = "Content", description = "CRUD operations for parsed Kodik content")
public class ContentController {

    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "title", "year", "created_at", "updated_at", "kinopoisk_id", "type");
    private static final Set<String> ALLOWED_ORDERS = Set.of("ASC", "DESC");

    private final ContentService contentService;

    @GetMapping
    @Operation(summary = "List all content with pagination")
    public ResponseEntity<PageResponse<ContentDto>> findAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "ASC") String order) {
        String safeSortBy = ALLOWED_SORT_FIELDS.contains(sortBy) ? sortBy : "id";
        String safeOrder =
                ALLOWED_ORDERS.contains(order.toUpperCase()) ? order.toUpperCase() : "ASC";

        PageRequest pageRequest =
                PageRequest.builder()
                        .page(page)
                        .size(size)
                        .sortBy(safeSortBy)
                        .order(safeOrder)
                        .build();
        return ResponseEntity.ok(contentService.findAll(pageRequest));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get content by ID")
    public ResponseEntity<ContentDto> findById(@PathVariable Long id) {
        return contentService
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/variants")
    @Operation(summary = "Get all episode variants for content")
    public ResponseEntity<List<EpisodeVariantDto>> findVariants(@PathVariable Long id) {
        return ResponseEntity.ok(contentService.findVariantsByContentId(id));
    }

    @GetMapping("/by-kinopoisk/{kinopoiskId}")
    @Operation(summary = "Find content by Kinopoisk ID")
    public ResponseEntity<ContentDto> findByKinopoiskId(@PathVariable String kinopoiskId) {
        return contentService
                .findByKinopoiskId(kinopoiskId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
