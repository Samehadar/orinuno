package com.orinuno.jutsu.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class JutsuRateLimiterTest {

    @Test
    void firstAcquireDoesNotBlock() {
        JutsuRateLimiter limiter = new JutsuRateLimiter(() -> 1.0, new SimpleMeterRegistry());
        Instant start = Instant.now();
        StepVerifier.create(limiter.acquire()).verifyComplete();
        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed).isLessThan(Duration.ofMillis(200));
    }

    @Test
    void secondAcquireWaitsRoughlyOneSecondAtOneRps() {
        JutsuRateLimiter limiter = new JutsuRateLimiter(() -> 1.0, new SimpleMeterRegistry());
        // Burn the first token immediately so the second call MUST wait for refill.
        limiter.acquire().block();
        Instant start = Instant.now();
        limiter.acquire().block(Duration.ofSeconds(3));
        Duration elapsed = Duration.between(start, Instant.now());
        // Bucket4j refills proportionally; assert the wait was at least 800ms (give a bit of
        // slack for clock granularity and Reactor scheduling) but did not exceed 2s (otherwise
        // the limiter is misconfigured).
        assertThat(elapsed)
                .isGreaterThanOrEqualTo(Duration.ofMillis(800))
                .isLessThan(Duration.ofMillis(2000));
    }

    @Test
    void hotSwappedRpsRebuildsBucketWithoutRestart() {
        AtomicReference<Double> rps = new AtomicReference<>(1.0);
        JutsuRateLimiter limiter = new JutsuRateLimiter(rps::get, new SimpleMeterRegistry());
        assertThat(limiter.currentRequestsPerSecond()).isEqualTo(1.0);
        // Bump the RPS via the live supplier — emulates a config-server hot reload.
        rps.set(5.0);
        // Force a new acquire so the limiter notices the new value and rebuilds.
        limiter.acquire().block();
        assertThat(limiter.currentRequestsPerSecond()).isEqualTo(5.0);
    }

    @Test
    void rpsBelowFloorIsClampedTo01() {
        JutsuRateLimiter limiter = new JutsuRateLimiter(() -> 0.0, new SimpleMeterRegistry());
        assertThat(limiter.currentRequestsPerSecond()).isEqualTo(0.1);
        // Should still issue a token without blocking on first call.
        StepVerifier.create(limiter.acquire()).verifyComplete();
    }

    @Test
    void negativeRpsIsClampedToFloor() {
        JutsuRateLimiter limiter = new JutsuRateLimiter(() -> -2.0, new SimpleMeterRegistry());
        assertThat(limiter.currentRequestsPerSecond()).isEqualTo(0.1);
    }

    @Test
    void nullMeterRegistryFallsBackToInternalNoOp() {
        // Consumers without micrometer-prometheus wired in must still get a working bucket.
        JutsuRateLimiter limiter = new JutsuRateLimiter(() -> 1.0, null);
        StepVerifier.create(limiter.acquire()).verifyComplete();
        assertThat(limiter.currentRequestsPerSecond()).isEqualTo(1.0);
    }
}
