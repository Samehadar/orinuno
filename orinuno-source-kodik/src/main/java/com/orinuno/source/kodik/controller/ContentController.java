/*
 * ContentController — ADR 0021 §C1.1.
 *
 * Read-only catalog of Kodik L1 content + variants. Ported verbatim
 * (field-for-field) from orinuno-app/.../controller/ContentController.java
 * so the demo UI keeps working unchanged after C1.2 flips the
 * /api/v1/content/* prefix into KodikUpstreamProxyFilter.PROXY_PREFIXES.
 *
 * Reactive shape (Mono + boundedElastic) matches the rest of source-kodik's
 * controllers — the underlying MyBatis reads are blocking, so we hand them
 * to an elastic scheduler instead of holding a Netty worker.
 */
package com.orinuno.source.kodik.controller;

import com.orinuno.source.kodik.model.dto.ContentDto;
import com.orinuno.source.kodik.model.dto.EpisodeVariantDto;
import com.orinuno.source.kodik.model.dto.PageRequest;
import com.orinuno.source.kodik.model.dto.PageResponse;
import com.orinuno.source.kodik.service.ContentReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/api/v1/content")
@RequiredArgsConstructor
@Tag(name = "Content", description = "CRUD reads over parsed Kodik content (ADR 0021 §C1)")
public class ContentController {

    private static final Set<String> ALLOWED_SORT_FIELDS =
            Set.of("id", "title", "year", "created_at", "updated_at", "kinopoisk_id", "type");
    private static final Set<String> ALLOWED_ORDERS = Set.of("ASC", "DESC");

    private final ContentReadService contentService;

    @GetMapping
    @Operation(summary = "List all content with pagination")
    public Mono<ResponseEntity<PageResponse<ContentDto>>> findAll(
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
        return Mono.fromCallable(() -> ResponseEntity.ok(contentService.findAll(pageRequest)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get content by ID")
    public Mono<ResponseEntity<ContentDto>> findById(@PathVariable Long id) {
        return Mono.fromCallable(() -> contentService.findById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(opt -> opt.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()));
    }

    @GetMapping("/{id}/variants")
    @Operation(summary = "Get all episode variants for content")
    public Mono<ResponseEntity<List<EpisodeVariantDto>>> findVariants(@PathVariable Long id) {
        return Mono.fromCallable(() -> contentService.findVariantsByContentId(id))
                .subscribeOn(Schedulers.boundedElastic())
                .map(ResponseEntity::ok);
    }

    @GetMapping("/by-kinopoisk/{kinopoiskId}")
    @Operation(summary = "Find content by Kinopoisk ID")
    public Mono<ResponseEntity<ContentDto>> findByKinopoiskId(@PathVariable String kinopoiskId) {
        return Mono.fromCallable(() -> contentService.findByKinopoiskId(kinopoiskId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(opt -> opt.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()));
    }
}
