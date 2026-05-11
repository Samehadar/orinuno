/*
 * CatalogReadCacheTest — ADR 0018 Phase 5.7a invariant.
 *
 * Locks the cache contract:
 *
 *   1. First lookup delegates to the repository, second lookup hits cache.
 *   2. Empty Optional results are cached too (negative-cache stampede guard).
 *   3. evictById forces a fresh load on next lookup.
 *
 * Pure Mockito + Caffeine test — no DB, no Spring context.
 */
package com.orinuno.catalog.readonly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogReadCache — ADR 0018 Phase 5.7a cache contract")
class CatalogReadCacheTest {

    @Mock private CatalogContentReadRepository delegate;

    @Test
    @DisplayName("first lookup hits delegate; second lookup served from cache")
    void cachesHits() {
        CatalogContentRow row = sampleRow(7L);
        when(delegate.findById(7L)).thenReturn(Optional.of(row));

        CatalogReadCache cache = newCache();
        assertThat(cache.findById(7L)).contains(row);
        assertThat(cache.findById(7L)).contains(row);

        verify(delegate, times(1)).findById(7L);
    }

    @Test
    @DisplayName("empty Optional is cached too — stampede guard for non-existent ids")
    void cachesMisses() {
        when(delegate.findById(999L)).thenReturn(Optional.empty());

        CatalogReadCache cache = newCache();
        assertThat(cache.findById(999L)).isEmpty();
        assertThat(cache.findById(999L)).isEmpty();
        assertThat(cache.findById(999L)).isEmpty();

        verify(delegate, times(1)).findById(999L);
    }

    @Test
    @DisplayName("evictById forces a fresh load on next lookup")
    void evictRefreshesValue() {
        CatalogContentRow first = sampleRow(7L);
        CatalogContentRow second =
                new CatalogContentRow(
                        7L,
                        "Updated",
                        null,
                        "movie",
                        2026,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        first.createdAt(),
                        LocalDateTime.now());
        when(delegate.findById(7L)).thenReturn(Optional.of(first), Optional.of(second));

        CatalogReadCache cache = newCache();
        assertThat(cache.findById(7L)).contains(first);
        cache.evictById(7L);
        assertThat(cache.findById(7L)).contains(second);

        verify(delegate, times(2)).findById(7L);
    }

    private CatalogReadCache newCache() {
        // Very long TTLs + small cache: the test asserts behaviour at the API surface,
        // not Caffeine's expiration mechanics. Refresh-after-write < expire keeps the
        // ratio realistic without making the test time-sensitive.
        return new CatalogReadCache(delegate, 3600, 600, 1024);
    }

    private static CatalogContentRow sampleRow(long id) {
        LocalDateTime now = LocalDateTime.of(2026, 5, 11, 10, 0);
        return new CatalogContentRow(
                id,
                "Кризис",
                "Crisis",
                "movie",
                2026,
                "4242",
                null,
                "tt99",
                "4242424242",
                null,
                null,
                now,
                now);
    }
}
