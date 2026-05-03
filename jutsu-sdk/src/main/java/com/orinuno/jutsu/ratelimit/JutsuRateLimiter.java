package com.orinuno.jutsu.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.function.DoubleSupplier;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Outbound rate limiter for every HTTP call we make to {@code jut.su} (login + episode page
 * fetches). Backed by a single Bucket4j token bucket sized from a configurable RPS.
 *
 * <p>Why we need this even though jut.su has no published rate limit:
 *
 * <ul>
 *   <li>The cookie session is tied to a real {@code Jutsu+} subscription account. Hammering jut.su
 *       from a single account is the fastest way to get the account flagged or banned, after which
 *       no amount of decoder cleverness will help.
 *   <li>jut.su uses bot-detection signals (request cadence is one of them); pacing requests at
 *       human-browsing speed reduces the chance of falling into a Cloudflare challenge loop.
 *   <li>Hard cap, not an SLO: this is a ceiling, not a target. The decoder is allowed to be slower
 *       than {@code rateLimitRps} — it's never allowed to be faster.
 * </ul>
 *
 * <p>The bucket is process-local (one per replica). For a multi-replica deployment with a single
 * shared {@code Jutsu+} account, divide {@code rateLimitRps} by replica count or move to a shared
 * bucket store. We don't have multi-replica today so this is parked.
 *
 * <p>Reactive contract: {@link #acquire()} returns a {@code Mono<Void>} that completes once a token
 * has been issued. When the bucket is empty it suspends via {@link Mono#delay} for the exact refill
 * interval Bucket4j reports — no busy-spinning, no thread blocking.
 *
 * <p>Construction takes a {@link DoubleSupplier} for the configured RPS rather than a constant so
 * orinuno-app can hot-swap the value via {@code OrinunoProperties} without rebuilding the bean.
 * Pass {@code () -> 1.0} for a fixed limit.
 */
@Slf4j
public final class JutsuRateLimiter {

    private final DoubleSupplier rpsSupplier;
    private final Counter throttleCounter;

    private volatile Bucket bucket;
    private volatile double currentRps;

    public JutsuRateLimiter(DoubleSupplier rpsSupplier, @Nullable MeterRegistry meterRegistry) {
        this.rpsSupplier = rpsSupplier;
        MeterRegistry registry = meterRegistry == null ? new SimpleMeterRegistry() : meterRegistry;
        this.throttleCounter =
                Counter.builder("orinuno.providers.jutsu.rate_limit.throttle.total")
                        .description("Outbound jut.su requests deferred by the rate limiter")
                        .register(registry);
        this.currentRps = effectiveRps();
        this.bucket = newBucket(this.currentRps);
        log.info("🪣 JutSu outbound rate limiter initialised at {} req/sec", currentRps);
    }

    /**
     * Block reactively until a token is available. Repeats every refill window if the bucket is
     * still empty after the first wait — defensive, because if the configured RPS gets hot-swapped
     * to a smaller value we don't want to ship a single token that the new bucket would have
     * refused.
     */
    public Mono<Void> acquire() {
        return Mono.defer(this::tryConsumeOnce)
                .flatMap(
                        wait -> {
                            if (wait.isZero() || wait.isNegative()) {
                                return Mono.empty();
                            }
                            throttleCounter.increment();
                            log.debug(
                                    "🚦 JutSu outbound rate-limit hit, sleeping {}ms",
                                    wait.toMillis());
                            return Mono.delay(wait).then(acquire());
                        });
    }

    private Mono<Duration> tryConsumeOnce() {
        double configured = effectiveRps();
        if (configured != currentRps) {
            synchronized (this) {
                if (configured != currentRps) {
                    log.info(
                            "🔁 JutSu outbound rate limit changed: {} → {} req/sec",
                            currentRps,
                            configured);
                    bucket = newBucket(configured);
                    currentRps = configured;
                }
            }
        }
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return Mono.just(Duration.ZERO);
        }
        return Mono.just(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private double effectiveRps() {
        double configured = rpsSupplier.getAsDouble();
        // Floor at 0.1 req/sec (one request every 10 seconds). Bucket4j refuses zero/negative
        // capacity; capping low protects us from a misconfigured 0 disabling the decoder
        // entirely with no error signal, and from a negative value bouncing the app on startup.
        return Math.max(0.1, configured);
    }

    private static Bucket newBucket(double rps) {
        // Translate "X requests per second" into Bucket4j's (capacity, refill-window) pair. We
        // pick a 1-second window with capacity = rps so consumers can see a small burst (e.g.
        // 1.0 rps → "send 1 immediately, then 1 every second" rather than "always wait 1 sec
        // before the first request"). For sub-1 rps values we widen the window to keep capacity
        // an integer ≥ 1.
        long capacity;
        Duration window;
        if (rps >= 1.0) {
            capacity = (long) Math.floor(rps);
            window = Duration.ofSeconds(1);
        } else {
            capacity = 1;
            window = Duration.ofMillis((long) Math.ceil(1000.0 / rps));
        }
        Bandwidth limit =
                Bandwidth.builder().capacity(capacity).refillGreedy(capacity, window).build();
        return Bucket.builder().addLimit(limit).build();
    }

    public double currentRequestsPerSecond() {
        return currentRps;
    }
}
