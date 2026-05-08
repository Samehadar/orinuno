package com.orinuno.jutsu.fallback;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Live fallback for jut.su REST endpoints (ARCH-0016 P1a Step 3.B). Sits on the cache-miss path
 * after {@code JutsuCatalogReadService}, exposing the same SDK surface ({@link JutsuCatalogPage},
 * {@link JutsuAnimeInfo}) but wrapped in three guards stacked from cheapest to most expensive:
 *
 * <ol>
 *   <li><b>Manual switch</b> ({@code orinuno.providers.jutsu.fallback.enabled}). When false the
 *       service short-circuits with {@link FallbackDisabledException} — REST controllers map this
 *       to 503. Cheap, zero allocations.
 *   <li><b>Negative cache</b>. A short-lived marker cache of recent failures, keyed by request
 *       fingerprint. A hit raises {@link NegativeCacheHitException}; suppresses retry storms when
 *       jut.su is degraded for one specific URL but otherwise healthy.
 *   <li><b>Circuit breaker</b>. Rolling-window breaker that opens when ≥50% of the last 20 fallback
 *       calls failed; raises {@link BreakerOpenException} during OPEN. Auto-recovers via HALF_OPEN
 *       after 60s (defaults; see {@code FallbackProperties}).
 *   <li><b>Rate limit</b>. Dedicated bucket (default 0.5 RPS) so a flood of cache-misses can't
 *       starve the sync worker that shares jut.su. Always acquired; the breaker / negative cache
 *       run before the limiter so we don't waste the bucket on doomed calls.
 * </ol>
 *
 * <p>Outcome accounting: every admitted live call emits exactly one {@link
 * JutsuFallbackCircuitBreaker#recordSuccess()} or {@link
 * JutsuFallbackCircuitBreaker#recordFailure()} via {@code .doOnSuccess}/{@code .doOnError} — that's
 * how the breaker's window stays in sync with reality. Failed calls also seed the negative cache.
 */
@Slf4j
public class JutsuLiveFallbackService {

    private final JutsuClient client;
    private final JutsuRateLimiter fallbackRateLimiter;
    private final JutsuFallbackCircuitBreaker breaker;
    private final JutsuFallbackNegativeCache negativeCache;
    private final boolean enabled;
    @Nullable private final MeterRegistry meterRegistry;

    public JutsuLiveFallbackService(
            JutsuClient client,
            JutsuRateLimiter fallbackRateLimiter,
            JutsuFallbackCircuitBreaker breaker,
            JutsuFallbackNegativeCache negativeCache,
            boolean enabled,
            @Nullable MeterRegistry meterRegistry) {
        this.client = client;
        this.fallbackRateLimiter = fallbackRateLimiter;
        this.breaker = breaker;
        this.negativeCache = negativeCache;
        this.enabled = enabled;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Live-fetch one catalog page through all three guards. Cache key is the resolved path + page +
     * search-query tuple so the same filter / page combination dedupes, but failing on page 2
     * doesn't block page 1 of the same filter (page is part of the key).
     */
    public Mono<JutsuCatalogPage> liveBrowseCatalog(JutsuCatalogRequest request) {
        String key = catalogKey(request);
        return runGuarded(key, () -> client.browseCatalog(request));
    }

    /**
     * Visible for tests — the negative-cache key for a catalog request. Stable wrt filter
     * permutations because the path slug is itself canonicalised by {@code JutsuFilterSlugger}.
     */
    public static String catalogKey(JutsuCatalogRequest request) {
        return "catalog:"
                + request.resolvePath()
                + ":"
                + request.page()
                + ":"
                + (request.hasSearch() ? request.searchQuery() : "");
    }

    /** Live-fetch a single anime info page through all three guards. Key is {@code "info:slug"}. */
    public Mono<JutsuAnimeInfo> liveAnimeInfo(String slug) {
        if (slug == null || slug.isBlank()) {
            return Mono.error(new IllegalArgumentException("slug must not be blank"));
        }
        String key = "info:" + slug;
        return runGuarded(key, () -> client.getAnimeInfo(slug));
    }

    private <T> Mono<T> runGuarded(String key, java.util.function.Supplier<Mono<T>> live) {
        return Mono.defer(
                () -> {
                    if (!enabled) {
                        if (meterRegistry != null) {
                            meterRegistry.counter("orinuno.jutsu.fallback.disabled").increment();
                        }
                        return Mono.error(new FallbackDisabledException());
                    }
                    if (negativeCache.isMarked(key)) {
                        return Mono.error(new NegativeCacheHitException(key));
                    }
                    if (!breaker.tryAcquire()) {
                        return Mono.error(new BreakerOpenException(breaker.state()));
                    }
                    return fallbackRateLimiter
                            .acquire()
                            .then(live.get())
                            .doOnSuccess(
                                    v -> {
                                        breaker.recordSuccess();
                                        if (meterRegistry != null) {
                                            meterRegistry
                                                    .counter("orinuno.jutsu.fallback.success")
                                                    .increment();
                                        }
                                    })
                            .doOnError(
                                    err -> {
                                        breaker.recordFailure();
                                        negativeCache.put(key);
                                        if (meterRegistry != null) {
                                            meterRegistry
                                                    .counter(
                                                            "orinuno.jutsu.fallback.failure",
                                                            "exception",
                                                            err.getClass().getSimpleName())
                                                    .increment();
                                        }
                                        log.warn(
                                                "jutsu-fallback: live call failed for key={},"
                                                    + " breaker state={}, marking negative cache",
                                                key,
                                                breaker.state(),
                                                err);
                                    });
                });
    }

    public JutsuFallbackCircuitBreaker breaker() {
        return breaker;
    }

    public JutsuFallbackNegativeCache negativeCache() {
        return negativeCache;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Thrown when {@code orinuno.providers.jutsu.fallback.enabled=false}. Maps to 503. */
    public static final class FallbackDisabledException extends RuntimeException {
        public FallbackDisabledException() {
            super("jut.su live fallback is disabled by configuration");
        }
    }

    /** Thrown when the negative cache marks a key as recently-failed. Maps to 503. */
    public static final class NegativeCacheHitException extends RuntimeException {
        private final String key;

        public NegativeCacheHitException(String key) {
            super("jut.su live fallback short-circuited by negative cache: " + key);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** Thrown when the circuit breaker is OPEN or HALF_OPEN with a probe in flight. Maps to 503. */
    public static final class BreakerOpenException extends RuntimeException {
        private final JutsuFallbackCircuitBreaker.State state;

        public BreakerOpenException(JutsuFallbackCircuitBreaker.State state) {
            super("jut.su live fallback short-circuited by circuit breaker: " + state);
            this.state = state;
        }

        public JutsuFallbackCircuitBreaker.State state() {
            return state;
        }
    }
}
