/*
 * CatalogController — ADR 0018 Phase 5.7.
 *
 * Public REST surface for canonical catalog reads. Thin pass-through over
 * CatalogReadCache (Phase 5.7a) which sits on top of CatalogContentReadRepository
 * (Phase 5.4) which reads from the shared catalog DB owned by `meter` (Phase 5.2).
 *
 * No HTTP hop to meter — orinuno talks to the same MySQL with SELECT-only grants.
 * meter outages freeze updates but keep the read-path serving the cached and
 * persisted state. orinuno is stateless w.r.t. catalog so multi-instance deploys
 * are safe (per-instance cache lag bounded by orinuno.catalog.cache.expire-after-write).
 *
 * Gating: @ConditionalOnBean(CatalogReadCache) — the whole controller is absent
 * unless orinuno.catalog-read.url is set. Monolith / legacy deploys keep serving
 * catalog through the in-process CatalogPublicApi as before.
 */
package com.orinuno.controller;

import com.orinuno.catalog.readonly.CatalogContentRow;
import com.orinuno.catalog.readonly.CatalogReadCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Gated on the same Environment property as the CatalogReadCache + the catalog
// readonly repos. @ConditionalOnBean on @RestController was racy after the orinuno-app
// source tree shrank; see CatalogEpisodeSourceReadRepository.
@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "orinuno.catalog-read", name = "url")
@Tag(
        name = "Catalog",
        description =
                "Canonical catalog reads from the shared catalog DB (ADR 0018 Phase 5). orinuno"
                        + " serves these endpoints directly via a read-only datasource + Caffeine"
                        + " cache; meter is the single writer of the underlying tables.")
public class CatalogController {

    private final CatalogReadCache cache;

    @GetMapping("/content/{id}")
    @Operation(summary = "Find one canonical catalog content row by id")
    public ResponseEntity<CatalogContentRow> findById(@PathVariable long id) {
        return cache.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
