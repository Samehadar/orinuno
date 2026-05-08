package com.orinuno.jutsu.fallback;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JutsuFallbackCircuitBreakerTest {

    /** Bare-minimum mutable clock so we can fast-forward past {@code openPause}. */
    private static class TestClock extends Clock {
        private final AtomicReference<Instant> now;

        TestClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    @Test
    @DisplayName("CLOSED stays CLOSED while window is filling, regardless of failure rate")
    void closedStaysClosedUntilWindowFills() {
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(
                        10, 0.5, Duration.ofSeconds(60), Clock.systemUTC(), null);

        // 9 failures with windowSize=10 — failure rate is 1.0 but the window isn't full yet, so
        // the breaker MUST NOT open. This is the early-burst protection: a single burst right
        // after startup shouldn't trip the breaker.
        for (int i = 0; i < 9; i++) {
            assertThat(breaker.tryAcquire()).isTrue();
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName(
            "CLOSED → OPEN once the window is full and failure rate ≥ threshold; subsequent"
                    + " tryAcquire() short-circuits without admitting calls")
    void opensOnceWindowFullAndRateExceedsThreshold() {
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(
                        10, 0.5, Duration.ofSeconds(60), Clock.systemUTC(), null);

        // 5 successes + 5 failures — exactly at the 50% threshold, breaker opens.
        for (int i = 0; i < 5; i++) {
            breaker.tryAcquire();
            breaker.recordSuccess();
        }
        for (int i = 0; i < 5; i++) {
            breaker.tryAcquire();
            breaker.recordFailure();
        }
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.OPEN);

        long shortCircuitsBefore = breaker.shortCircuits();
        assertThat(breaker.tryAcquire()).isFalse();
        assertThat(breaker.shortCircuits()).isEqualTo(shortCircuitsBefore + 1);
    }

    @Test
    @DisplayName(
            "OPEN → HALF_OPEN after openPause elapses; admits exactly one probe, blocks"
                    + " concurrent calls until probe outcome is recorded")
    void openTransitionsToHalfOpenAfterPause() {
        TestClock clock = new TestClock(Instant.parse("2026-05-08T03:00:00Z"));
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(2, 0.5, Duration.ofSeconds(60), clock, null);

        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.OPEN);

        // 30s in — still OPEN, all calls rejected.
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isFalse();

        // 90s in (past 60s pause) — probe is admitted, state flips to HALF_OPEN under the hood.
        clock.advance(Duration.ofSeconds(60));
        assertThat(breaker.tryAcquire()).as("first probe after pause is admitted").isTrue();
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.HALF_OPEN);

        // While the probe is in flight, concurrent calls MUST short-circuit.
        assertThat(breaker.tryAcquire())
                .as("HALF_OPEN admits a single probe at a time — concurrent calls reject")
                .isFalse();
    }

    @Test
    @DisplayName("HALF_OPEN probe success → CLOSED with cleared window")
    void halfOpenProbeSuccessClearsWindow() {
        TestClock clock = new TestClock(Instant.parse("2026-05-08T03:00:00Z"));
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(2, 0.5, Duration.ofSeconds(60), clock, null);

        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.OPEN);

        clock.advance(Duration.ofSeconds(61));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordSuccess();

        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.CLOSED);
        // Window must have been cleared — failureRate over an empty window is 0, and the next
        // failure shouldn't immediately re-open (windowSize=2 isn't reached after 1 failure).
        assertThat(breaker.failureRate()).isZero();
        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("HALF_OPEN probe failure → OPEN with refreshed openedAt timestamp")
    void halfOpenProbeFailureReopens() {
        TestClock clock = new TestClock(Instant.parse("2026-05-08T03:00:00Z"));
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(2, 0.5, Duration.ofSeconds(60), clock, null);

        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();

        clock.advance(Duration.ofSeconds(61));
        assertThat(breaker.tryAcquire()).isTrue();
        breaker.recordFailure();

        assertThat(breaker.state()).isEqualTo(JutsuFallbackCircuitBreaker.State.OPEN);
        // Re-opened breaker must wait the FULL pause again (clock is now t+61, openedAt resets
        // to t+61). Without the refresh the breaker would flap continuously.
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.tryAcquire()).isFalse();
        clock.advance(Duration.ofSeconds(31)); // total 61s since reopen
        assertThat(breaker.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("counters track opens, probes, and short-circuits")
    void countersTrackLifecycleEvents() {
        TestClock clock = new TestClock(Instant.parse("2026-05-08T03:00:00Z"));
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(2, 0.5, Duration.ofSeconds(60), clock, null);

        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();
        assertThat(breaker.opens()).isEqualTo(1);

        breaker.tryAcquire(); // short-circuited
        breaker.tryAcquire();
        assertThat(breaker.shortCircuits()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(61));
        breaker.tryAcquire(); // probe admitted (HALF_OPEN)
        assertThat(breaker.probesAdmitted()).isEqualTo(1);
    }
}
