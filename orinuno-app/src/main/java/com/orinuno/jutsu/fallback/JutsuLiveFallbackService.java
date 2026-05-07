package com.orinuno.jutsu.fallback;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.orinuno.configuration.JutsuLiveFallbackProperties;
import com.orinuno.jutsu.drift.JutsuDriftException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Owns the hybrid-fallback guards from ADR 0016 §"REST cutover for jut.su":
 *
 * <ol>
 *   <li>Bucket4j rate limit, per X-API-KEY (or remote IP), 1 req / 5s default.
 *   <li>Caffeine negative cache, 24h TTL, key = slug. ONLY populated on a clean "no such slug"
 *       signal (404 / 410 / supplier returning {@code null}). Transient / network / drift errors do
 *       NOT poison the cache.
 *   <li>Kill-switch via {@code orinuno.jutsu.live-fallback.enabled} (default {@code false}; the dev
 *       profile flips it on).
 *   <li>Force-refresh via {@code refresh=true} that requires a non-anonymous {@code X-API-KEY}.
 *   <li>{@code jutsu_live_fallback_total} Micrometer counter tagged with the outcome.
 *   <li>Per-consumer Bucket4j map is a Caffeine cache with {@code expireAfterAccess} so a public
 *       endpoint can't blow up heap by iterating IPs.
 * </ol>
 *
 * <p>Two entry points:
 *
 * <ul>
 *   <li>{@link #dispatch(String, String, boolean, String, Supplier)} — synchronous, used by
 *       background callers and unit tests.
 *   <li>{@link #dispatchReactive(String, String, boolean, String, Supplier)} — used by the reactive
 *       controller, NEVER blocks the event loop.
 * </ul>
 */
@Slf4j
public class JutsuLiveFallbackService {

    static final String API_KEY_HEADER = "X-API-KEY";
    static final String METRIC_NAME = "jutsu_live_fallback_total";

    private final JutsuLiveFallbackProperties properties;
    private final MeterRegistry meterRegistry;

    private final Cache<String, Bucket> buckets;
    private final Map<JutsuLiveFallbackOutcome, Counter> counters = new ConcurrentHashMap<>();
    private final Cache<String, Boolean> negativeCache;

    public JutsuLiveFallbackService(
            JutsuLiveFallbackProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.negativeCache =
                Caffeine.newBuilder()
                        .expireAfterWrite(
                                properties.negativeCache().effectiveTtlHours(), TimeUnit.HOURS)
                        .maximumSize(50_000)
                        .build();
        this.buckets =
                Caffeine.newBuilder()
                        .expireAfterAccess(
                                properties.buckets().effectiveExpireAfterAccessHours(),
                                TimeUnit.HOURS)
                        .maximumSize(properties.buckets().effectiveMaxSize())
                        .build();
    }

    /**
     * Synchronous variant — see {@link #dispatchReactive(String, String, boolean, String,
     * Supplier)} for the reactive equivalent used by controllers.
     *
     * @param upstream may return {@code null} to signal "upstream said this slug doesn't exist";
     *     the slug then goes into the negative cache.
     */
    public <T> Optional<T> dispatch(
            String slug,
            String consumerKey,
            boolean refresh,
            @Nullable String providedApiKey,
            Supplier<T> upstream) {
        applyGuards(slug, consumerKey, refresh, providedApiKey);
        try {
            T result = upstream.get();
            if (result == null) {
                negativeCache.put(slug, Boolean.TRUE);
                recordOutcome(JutsuLiveFallbackOutcome.MISS);
                return Optional.empty();
            }
            recordOutcome(JutsuLiveFallbackOutcome.HIT);
            return Optional.of(result);
        } catch (RuntimeException ex) {
            return classifyAndThrow(slug, ex);
        }
    }

    /** Reactive variant — guards run synchronously, upstream call is composed into the chain. */
    public <T> Mono<Optional<T>> dispatchReactive(
            String slug,
            String consumerKey,
            boolean refresh,
            @Nullable String providedApiKey,
            Supplier<Mono<T>> upstream) {
        return Mono.defer(
                () -> {
                    applyGuards(slug, consumerKey, refresh, providedApiKey);
                    return upstream.get()
                            .map(
                                    value -> {
                                        recordOutcome(JutsuLiveFallbackOutcome.HIT);
                                        return Optional.of(value);
                                    })
                            .switchIfEmpty(
                                    Mono.fromSupplier(
                                            () -> {
                                                negativeCache.put(slug, Boolean.TRUE);
                                                recordOutcome(JutsuLiveFallbackOutcome.MISS);
                                                return Optional.<T>empty();
                                            }))
                            .onErrorResume(
                                    RuntimeException.class,
                                    ex ->
                                            Mono.fromCallable(
                                                    () -> {
                                                        Optional<T> empty =
                                                                classifyAndThrow(slug, ex);
                                                        return empty;
                                                    }));
                });
    }

    /** Evict a slug from the negative cache (manual override for ops). */
    public void evictNegative(String slug) {
        if (slug != null) negativeCache.invalidate(slug);
    }

    /** Visible-for-test accessor — the unit tests assert on the outcome counter values. */
    public double counterValue(JutsuLiveFallbackOutcome outcome) {
        Counter c = counters.get(outcome);
        return c == null ? 0d : c.count();
    }

    public boolean isEnabled() {
        return properties.enabled();
    }

    long bucketsSize() {
        return buckets.estimatedSize();
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private void applyGuards(
            String slug, String consumerKey, boolean refresh, @Nullable String providedApiKey) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("slug must not be blank");
        }
        if (!properties.enabled()) {
            recordOutcome(JutsuLiveFallbackOutcome.DISABLED);
            log.debug("⛔ jut.su live-fallback disabled by kill-switch (slug={})", sanitize(slug));
            throw new JutsuLiveFallbackException(
                    HttpStatus.NOT_FOUND,
                    JutsuLiveFallbackOutcome.DISABLED,
                    "live-fallback disabled by orinuno.jutsu.live-fallback.enabled=false",
                    0);
        }
        if (refresh) {
            if (providedApiKey == null || providedApiKey.isBlank()) {
                throw new JutsuLiveFallbackException(
                        HttpStatus.UNAUTHORIZED,
                        JutsuLiveFallbackOutcome.DISABLED,
                        "refresh=true requires a non-anonymous X-API-KEY",
                        0);
            }
        } else if (Boolean.TRUE.equals(negativeCache.getIfPresent(slug))) {
            recordOutcome(JutsuLiveFallbackOutcome.NEGATIVE_CACHE);
            throw new JutsuLiveFallbackException(
                    HttpStatus.NOT_FOUND,
                    JutsuLiveFallbackOutcome.NEGATIVE_CACHE,
                    "slug is negatively cached",
                    0);
        }

        ConsumptionProbe probe = bucketFor(consumerKey).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter =
                    Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
            recordOutcome(JutsuLiveFallbackOutcome.RATE_LIMITED);
            log.warn(
                    "🚦 jut.su live-fallback rate-limited (consumer={} slug={} retryAfter={}s)",
                    sanitize(consumerKey),
                    sanitize(slug),
                    retryAfter);
            throw new JutsuLiveFallbackException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    JutsuLiveFallbackOutcome.RATE_LIMITED,
                    "live-fallback rate limit exceeded",
                    retryAfter);
        }
    }

    /**
     * Classify a runtime exception thrown by the upstream supplier:
     *
     * <ul>
     *   <li>HTTP 404 / 410 → "no such slug" → negative cache, return empty Optional.
     *   <li>Drift / 5xx / network IO / timeout / unknown → upstream error, propagate to caller as
     *       {@link JutsuLiveFallbackException} with status 502.
     * </ul>
     *
     * @return never returns normally for the "upstream error" branch; declared for {@code <T>}
     *     inference at the call site.
     */
    private <T> Optional<T> classifyAndThrow(String slug, RuntimeException ex) {
        if (isNotFound(ex)) {
            negativeCache.put(slug, Boolean.TRUE);
            recordOutcome(JutsuLiveFallbackOutcome.MISS);
            log.debug(
                    "🪦 jut.su live-fallback: slug={} → 404 from upstream, negatively cached",
                    sanitize(slug));
            return Optional.empty();
        }
        recordOutcome(JutsuLiveFallbackOutcome.UPSTREAM_ERROR);
        log.warn(
                "⚠️ jut.su live-fallback upstream transient error; slug={} NOT cached",
                sanitize(slug),
                ex);
        throw new JutsuLiveFallbackException(
                HttpStatus.BAD_GATEWAY,
                JutsuLiveFallbackOutcome.UPSTREAM_ERROR,
                "jut.su upstream call failed: " + ex.getClass().getSimpleName(),
                0);
    }

    private static boolean isNotFound(RuntimeException ex) {
        if (ex instanceof JutsuDriftException) return false;
        if (ex instanceof WebClientResponseException wcre) {
            return wcre.getStatusCode() == HttpStatus.NOT_FOUND
                    || wcre.getStatusCode() == HttpStatus.GONE;
        }
        // Unwrap one level of common reactive-stream wrappers.
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof WebClientResponseException wcre) {
                return wcre.getStatusCode() == HttpStatus.NOT_FOUND
                        || wcre.getStatusCode() == HttpStatus.GONE;
            }
            if (cause instanceof IOException
                    || cause instanceof TimeoutException
                    || cause instanceof WebClientRequestException) {
                return false;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private Bucket bucketFor(String consumerKey) {
        return buckets.get(consumerKey, k -> newBucket());
    }

    private Bucket newBucket() {
        double rps = properties.rateLimit().effectiveRequestsPerSecond();
        long capacity = Math.max(1L, (long) Math.ceil(rps));
        long periodSeconds = Math.max(1L, (long) Math.ceil(1.0 / rps));
        Bandwidth bandwidth =
                Bandwidth.builder()
                        .capacity(capacity)
                        .refillGreedy(capacity, Duration.ofSeconds(periodSeconds))
                        .build();
        return Bucket.builder().addLimit(bandwidth).build();
    }

    private void recordOutcome(JutsuLiveFallbackOutcome outcome) {
        counters.computeIfAbsent(outcome, this::newCounter).increment();
    }

    private Counter newCounter(JutsuLiveFallbackOutcome outcome) {
        return Counter.builder(METRIC_NAME)
                .description("jut.su live-fallback dispatch outcomes")
                .tags(Tags.of(Tag.of("outcome", outcome.tag())))
                .register(meterRegistry);
    }

    private static String sanitize(String s) {
        if (s == null) return "null";
        return s.replace('\n', '_').replace('\r', '_');
    }
}
