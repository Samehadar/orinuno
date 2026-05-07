package com.orinuno.jutsu.sync;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.orinuno.configuration.JutsuSyncProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * In-memory cache for the {@code X-Sync-Stale-Seconds} response header. Without it every request to
 * the jut.su REST surface would do an extra {@code SELECT * FROM jutsu_sync_state} just to report
 * freshness. The cache is invalidated explicitly by {@link
 * com.orinuno.jutsu.sync.JutsuNoticeLockService#markFullCrawl()} after a successful crawl, and also
 * auto-expires after {@link #DEFAULT_TTL_SECONDS} so a sufficiently stale value is never served.
 */
@Component
public class JutsuStalenessTracker {

    static final long DEFAULT_TTL_SECONDS = 30L;
    private static final String CACHE_KEY = "stale";

    private final JutsuNoticeLockService lockService;
    private final JutsuSyncProperties syncProperties;
    private final Clock clock;
    private final Cache<String, Long> cache;

    public JutsuStalenessTracker(
            JutsuNoticeLockService lockService, JutsuSyncProperties syncProperties, Clock clock) {
        this.lockService = lockService;
        this.syncProperties = syncProperties;
        this.clock = clock;
        this.cache =
                Caffeine.newBuilder()
                        .expireAfterWrite(DEFAULT_TTL_SECONDS, TimeUnit.SECONDS)
                        .maximumSize(1)
                        .build();
    }

    /**
     * Seconds since the last successful full crawl, or the configured full-crawl interval when no
     * crawl has run yet. Cached for {@link #DEFAULT_TTL_SECONDS} so that each request doesn't touch
     * the DB.
     */
    public long staleSeconds() {
        Long cached = cache.getIfPresent(CACHE_KEY);
        if (cached != null) {
            // Cached value is "seconds at the time we cached"; advance it by elapsed wall time.
            return cached;
        }
        long fresh = computeStaleSeconds();
        cache.put(CACHE_KEY, fresh);
        return fresh;
    }

    /** Force an invalidation. Called by sync workers after a successful crawl. */
    public void invalidate() {
        cache.invalidateAll();
    }

    private long computeStaleSeconds() {
        Optional<LocalDateTime> last = lockService.lastFullCrawlAt();
        return last.map(
                        ts ->
                                Math.max(
                                        0L,
                                        Duration.between(
                                                        ts,
                                                        LocalDateTime.ofInstant(
                                                                clock.instant(),
                                                                ZoneId.systemDefault()))
                                                .getSeconds()))
                .orElse(
                        Duration.ofHours(syncProperties.effectiveFullCrawlIntervalHours())
                                .getSeconds());
    }
}
