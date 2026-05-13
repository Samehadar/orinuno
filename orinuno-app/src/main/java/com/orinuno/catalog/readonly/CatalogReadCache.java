/*
 * CatalogReadCache — ADR 0018 Phase 5.7a.
 *
 * Caffeine-backed in-process cache over CatalogContentReadRepository. Sits between
 * the read-path consumers (CatalogController in Phase 5.7) and the underlying
 * JdbcTemplate so a multi-instance orinuno deploy can scale horizontally without
 * hammering the shared MySQL on every catalog lookup.
 *
 * Cadence:
 *   • expireAfterWrite — primary freshness bound (default 5 min). Catalog rows
 *     update on Kodik dump cycles (~24h) and jut.su incremental refreshes
 *     (hours), so 5 min is comfortably under the change rate.
 *   • refreshAfterWrite — stale-while-revalidate (default 1 min). After 1 min
 *     a hit returns the cached value AND triggers a background refresh on the
 *     async refresher, hiding the DB round-trip from p99 latency.
 *   • maximumSize — 50k entries (default). Sized for the active working set
 *     (popular titles only); the long tail is OK to bypass cache.
 *
 * All bounds are property-driven so deployers can tune per traffic shape. Eviction
 * happens automatically; an admin /api/v1/admin/catalog/cache/evict endpoint
 * (Phase 5.7b) ships separately if manual invalidation is wanted.
 *
 * Negative caching: misses (empty Optional) are cached too. A stampede on a
 * non-existent id costs one DB query instead of one per request. The same TTL
 * bound applies — a newly-inserted row appears in 5 min worst case.
 *
 * Gated on @ConditionalOnBean — only active when the read-only repository bean
 * exists (i.e. orinuno.catalog-read.url is set per Phase 5.4). In monolith mode
 * this whole tree stays absent and orinuno-app reads catalog the legacy way.
 */
package com.orinuno.catalog.readonly;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Gated on the same Environment property as CatalogReadDataSourceConfiguration. See
// CatalogEpisodeSourceReadRepository for why the chain switched away from the racy
// @ConditionalOnBean(Class) pattern.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "orinuno.catalog-read", name = "url")
public class CatalogReadCache {

    private final CatalogContentReadRepository delegate;
    private final LoadingCache<Long, Optional<CatalogContentRow>> byId;

    public CatalogReadCache(
            CatalogContentReadRepository delegate,
            @Value("${orinuno.catalog.cache.expire-after-write-seconds:300}")
                    long expireAfterWriteSeconds,
            @Value("${orinuno.catalog.cache.refresh-after-write-seconds:60}")
                    long refreshAfterWriteSeconds,
            @Value("${orinuno.catalog.cache.max-size:50000}") long maxSize) {
        this.delegate = delegate;
        this.byId =
                Caffeine.newBuilder()
                        .maximumSize(maxSize)
                        .expireAfterWrite(Duration.ofSeconds(expireAfterWriteSeconds))
                        .refreshAfterWrite(Duration.ofSeconds(refreshAfterWriteSeconds))
                        .recordStats()
                        .build(this::loadById);
        log.info(
                "CatalogReadCache: expireAfterWrite={}s, refreshAfterWrite={}s, maxSize={}",
                expireAfterWriteSeconds,
                refreshAfterWriteSeconds,
                maxSize);
    }

    public Optional<CatalogContentRow> findById(long id) {
        return byId.get(id);
    }

    /**
     * Evict a single key — useful after a server-side write completes so the next read sees fresh
     * state. Today nothing writes via orinuno-app; reserved for Phase 5.7b admin endpoint.
     */
    public void evictById(long id) {
        byId.invalidate(id);
    }

    /**
     * Snapshot the Caffeine stats — exposed via the diagnostics surface so ops can compare hit
     * ratio against the configured TTL and adjust if churn outruns the cache.
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats stats() {
        return byId.stats();
    }

    private Optional<CatalogContentRow> loadById(long id) {
        return delegate.findById(id);
    }
}
