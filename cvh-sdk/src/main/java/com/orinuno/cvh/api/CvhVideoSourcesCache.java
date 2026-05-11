package com.orinuno.cvh.api;

import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.model.CvhVideoSources;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Bounded in-memory cache of signed CDN URL bundles keyed by {@code vkId}. CVH signs URLs with a
 * ~24h TTL; the cache refetches when {@code expiresAt - now} drops below the configured margin so
 * callers never see a stale token.
 *
 * <p>Eviction is LRU — backed by a {@link LinkedHashMap} in {@code access-order} mode, wrapped in a
 * synchronized view so concurrent {@code getOrFetch}/{@code invalidate} calls remain consistent.
 * The cap is set by {@link CvhConfig#maxCacheEntries()} (default 1024). Without the bound, a
 * malicious caller could push unlimited distinct vkIds and OOM the process.
 */
@Slf4j
public final class CvhVideoSourcesCache {

    private final CvhApiClient apiClient;
    private final Duration refreshMargin;
    private final Clock clock;
    private final int maxEntries;
    private final Map<String, CvhVideoSources> cache;

    public CvhVideoSourcesCache(CvhApiClient apiClient, CvhConfig config) {
        this(apiClient, config, Clock.systemUTC());
    }

    public CvhVideoSourcesCache(CvhApiClient apiClient, CvhConfig config, Clock clock) {
        this.apiClient = apiClient;
        this.refreshMargin = Duration.ofMinutes(config.tokenRefreshMarginMinutes());
        this.clock = clock;
        this.maxEntries = config.maxCacheEntries();
        this.cache =
                Collections.synchronizedMap(
                        new LinkedHashMap<String, CvhVideoSources>(16, 0.75f, true) {
                            @Override
                            protected boolean removeEldestEntry(
                                    Map.Entry<String, CvhVideoSources> eldest) {
                                return size() > maxEntries;
                            }
                        });
    }

    public Mono<CvhVideoSources> getOrFetch(String vkId) {
        return getOrFetch(vkId, null);
    }

    public Mono<CvhVideoSources> getOrFetch(String vkId, @Nullable String refererOverride) {
        CvhVideoSources cached = cache.get(vkId);
        if (cached != null && !isExpiringSoon(cached)) {
            return Mono.just(cached);
        }
        log.debug("Fetching fresh CVH sources vkId={}", vkId);
        return apiClient
                .getVideoSources(vkId, refererOverride)
                .doOnNext(fresh -> cache.put(vkId, fresh));
    }

    public void invalidate(String vkId) {
        cache.remove(vkId);
    }

    public int size() {
        return cache.size();
    }

    public int maxEntries() {
        return maxEntries;
    }

    private boolean isExpiringSoon(CvhVideoSources sources) {
        Instant expiresAt = sources.expiresAt();
        if (expiresAt == null) {
            return true;
        }
        Instant threshold = Instant.now(clock).plus(refreshMargin);
        return expiresAt.isBefore(threshold);
    }
}
