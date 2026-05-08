package com.orinuno.jutsu.fallback;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Short-TTL cache of fallback failures keyed by lookup descriptor (catalog query fingerprint or
 * anime slug). Used by {@link JutsuLiveFallbackService} to deduplicate concurrent / repeat
 * cache-miss requests for the same key — once one fallback has failed, subsequent requests
 * short-circuit for {@code ttl} instead of replaying the same doomed call.
 *
 * <p>Successful fallback results are NOT cached here — they go to the L1 cache via the sync workers
 * (which run on a much longer cadence). The negative cache only ever holds {@code true} markers;
 * {@link #put(String)} simply records "the key just failed", and {@link #isMarked(String)} returns
 * whether a marker is still alive.
 *
 * <p>Backed by Caffeine with size-based + time-based eviction so a flood of unique keys can't blow
 * up the heap.
 */
public class JutsuFallbackNegativeCache {

    private final Cache<String, Boolean> cache;
    @Nullable private final MeterRegistry meterRegistry;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public JutsuFallbackNegativeCache(
            Duration ttl, long maxSize, @Nullable MeterRegistry meterRegistry) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build();
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge(
                    "orinuno.jutsu.fallback.negative_cache.size", cache, c -> c.estimatedSize());
        }
    }

    /** Mark {@code key} as recently-failed. Idempotent — calling twice resets the TTL window. */
    public void put(String key) {
        cache.put(key, Boolean.TRUE);
    }

    /**
     * @return {@code true} when a marker for {@code key} is still alive (caller must
     *     short-circuit), {@code false} when no marker exists or it has expired.
     */
    public boolean isMarked(String key) {
        boolean marked = cache.getIfPresent(key) != null;
        if (marked) {
            hits.incrementAndGet();
            if (meterRegistry != null) {
                meterRegistry.counter("orinuno.jutsu.fallback.negative_cache.hit").increment();
            }
        } else {
            misses.incrementAndGet();
            if (meterRegistry != null) {
                meterRegistry.counter("orinuno.jutsu.fallback.negative_cache.miss").increment();
            }
        }
        return marked;
    }

    /** Clears all markers — escape hatch for ops via an admin endpoint (added in Step 3.C). */
    public void clear() {
        cache.invalidateAll();
    }

    public long hits() {
        return hits.get();
    }

    public long misses() {
        return misses.get();
    }

    public long size() {
        return cache.estimatedSize();
    }
}
