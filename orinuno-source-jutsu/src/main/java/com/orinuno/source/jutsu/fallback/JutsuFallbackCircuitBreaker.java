package com.orinuno.source.jutsu.fallback;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/**
 * Self-written rolling-window circuit breaker for the jut.su live-fallback path (ARCH-0016 P1a Step
 * 3.B). Resilience4j is overkill for one call site and would drag a transitive Reactor / RxJava2
 * split into the orinuno-app module — a 50-line breaker with a unit test is cheaper.
 *
 * <p>State machine:
 *
 * <pre>
 *               recordFailure() raises rate ≥ threshold
 *      CLOSED ─────────────────────────────────────────► OPEN
 *        ▲                                                 │
 *        │                                                 │ {@code openPauseSeconds} elapses
 *        │ probe succeeds                                  ▼
 *      CLOSED ◄──────── HALF_OPEN ◄──────────────────── OPEN
 *                          │
 *                          │ probe fails
 *                          ▼
 *                         OPEN (reset openedAt → wait again)
 * </pre>
 *
 * <p>Decisions:
 *
 * <ul>
 *   <li>The failure rate is computed over the last {@code windowSize} outcomes — fixed-size
 *       circular history. While the window is filling (less than {@code windowSize} samples
 *       observed) the breaker stays CLOSED regardless of the rate, so a single early failure can't
 *       open it.
 *   <li>HALF_OPEN admits exactly one probe at a time (single-token semaphore via {@code
 *       probeInFlight}). Concurrent fallback requests during HALF_OPEN are rejected just like OPEN,
 *       until the probe completes.
 *   <li>Successful probe ⇒ window cleared + state → CLOSED. The cleared window means the breaker
 *       won't immediately re-open on the next failure; we want to give a recovering upstream a fair
 *       chance.
 *   <li>Counters and gauges are exposed via Micrometer when a registry is present. Stripped when
 *       {@code meterRegistry} is null so the class works in unit tests without a registry.
 * </ul>
 *
 * <p>Thread-safety: all state mutations are synchronised on {@code this}. The breaker is on the
 * cache-miss path (≪ requests / sec at our scale), so contention on a single monitor is fine. If
 * this ever needs to scale, swap {@link Deque} for a lock-free ring buffer — the public surface is
 * intentionally small.
 */
@Slf4j
public class JutsuFallbackCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int windowSize;
    private final double failureRateThreshold;
    private final Duration openPause;
    private final Clock clock;
    @Nullable private final MeterRegistry meterRegistry;

    private final Deque<Boolean> outcomes = new ArrayDeque<>();
    private State state = State.CLOSED;
    private Instant openedAt = Instant.EPOCH;
    private boolean probeInFlight = false;

    private final AtomicLong opens = new AtomicLong();
    private final AtomicLong probesAdmitted = new AtomicLong();
    private final AtomicLong shortCircuits = new AtomicLong();

    public JutsuFallbackCircuitBreaker(
            int windowSize,
            double failureRateThreshold,
            Duration openPause,
            Clock clock,
            @Nullable MeterRegistry meterRegistry) {
        if (windowSize <= 0) throw new IllegalArgumentException("windowSize must be > 0");
        if (failureRateThreshold <= 0.0 || failureRateThreshold > 1.0) {
            throw new IllegalArgumentException("failureRateThreshold must be in (0,1]");
        }
        this.windowSize = windowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.openPause = openPause;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge(
                    "orinuno.jutsu.fallback.breaker.state",
                    this,
                    b -> {
                        synchronized (b) {
                            return switch (b.state) {
                                case CLOSED -> 0;
                                case HALF_OPEN -> 1;
                                case OPEN -> 2;
                            };
                        }
                    });
            meterRegistry.gauge(
                    "orinuno.jutsu.fallback.breaker.failure_rate", this, b -> b.failureRate());
        }
    }

    /**
     * Decide whether a fallback call may proceed. Call this BEFORE invoking the live SDK; if it
     * returns {@code false} the caller must short-circuit (return 503 / cache-miss to upstream).
     * Caller MUST follow a {@code true} verdict with exactly one {@link #recordSuccess()} or {@link
     * #recordFailure()} so the window stays accurate.
     */
    public synchronized boolean tryAcquire() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (clock.instant().isAfter(openedAt.plus(openPause))) {
                    state = State.HALF_OPEN;
                    log.info(
                            "jutsu-fallback breaker: OPEN → HALF_OPEN after {}s pause",
                            openPause.toSeconds());
                    return tryAcquire(); // re-enter via HALF_OPEN
                }
                shortCircuits.incrementAndGet();
                if (meterRegistry != null) {
                    meterRegistry
                            .counter("orinuno.jutsu.fallback.breaker.short_circuit")
                            .increment();
                }
                return false;
            case HALF_OPEN:
                if (probeInFlight) {
                    shortCircuits.incrementAndGet();
                    if (meterRegistry != null) {
                        meterRegistry
                                .counter("orinuno.jutsu.fallback.breaker.short_circuit")
                                .increment();
                    }
                    return false;
                }
                probeInFlight = true;
                probesAdmitted.incrementAndGet();
                if (meterRegistry != null) {
                    meterRegistry
                            .counter("orinuno.jutsu.fallback.breaker.probe_admitted")
                            .increment();
                }
                return true;
            default:
                throw new IllegalStateException("unknown state: " + state);
        }
    }

    /** Caller must invoke after a successful live SDK call admitted via {@link #tryAcquire()}. */
    public synchronized void recordSuccess() {
        if (state == State.HALF_OPEN) {
            log.info("jutsu-fallback breaker: HALF_OPEN probe succeeded → CLOSED, window cleared");
            state = State.CLOSED;
            outcomes.clear();
            probeInFlight = false;
            return;
        }
        push(true);
    }

    /** Caller must invoke after a failed live SDK call admitted via {@link #tryAcquire()}. */
    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN) {
            log.warn("jutsu-fallback breaker: HALF_OPEN probe failed → OPEN again");
            state = State.OPEN;
            openedAt = clock.instant();
            probeInFlight = false;
            opens.incrementAndGet();
            if (meterRegistry != null) {
                meterRegistry.counter("orinuno.jutsu.fallback.breaker.opens").increment();
            }
            return;
        }
        push(false);
        if (outcomes.size() >= windowSize && failureRate() >= failureRateThreshold) {
            log.warn(
                    "jutsu-fallback breaker: failure rate {} ≥ threshold {} over last {} samples"
                            + " → OPEN for {}s",
                    failureRate(),
                    failureRateThreshold,
                    windowSize,
                    openPause.toSeconds());
            state = State.OPEN;
            openedAt = clock.instant();
            opens.incrementAndGet();
            if (meterRegistry != null) {
                meterRegistry.counter("orinuno.jutsu.fallback.breaker.opens").increment();
            }
        }
    }

    public synchronized State state() {
        return state;
    }

    /** Visible for tests / metrics — the failure rate over the current window. */
    public synchronized double failureRate() {
        if (outcomes.isEmpty()) return 0.0;
        long failures = outcomes.stream().filter(b -> !b).count();
        return (double) failures / outcomes.size();
    }

    public long shortCircuits() {
        return shortCircuits.get();
    }

    public long opens() {
        return opens.get();
    }

    public long probesAdmitted() {
        return probesAdmitted.get();
    }

    private void push(boolean success) {
        outcomes.addLast(success);
        while (outcomes.size() > windowSize) outcomes.removeFirst();
    }
}
