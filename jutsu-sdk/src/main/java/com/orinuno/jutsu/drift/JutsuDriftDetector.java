package com.orinuno.jutsu.drift;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe collector of {@link JutsuDriftEvent}s with a sliding window for "recent" events plus
 * lifetime counters per signal. The detector is shared by every parser/client in the SDK; the
 * orinuno-app exposes a single instance through {@link com.orinuno.jutsu.JutsuClient#driftDetector
 * JutsuClient}.
 *
 * <p>Hot path: {@link #observe(JutsuDriftEvent)} acquires a single {@code synchronized} on the
 * private deque and an {@link AtomicInteger#incrementAndGet} per signal. Snapshots build a
 * point-in-time copy under the same lock so the {@link MultiSourceRanker} reads coherent counts.
 *
 * <p>Health rules (see {@link JutsuDriftHealth}):
 *
 * <ul>
 *   <li>{@code UNAVAILABLE} — any {@link JutsuDriftSignal#UNEXPECTED_HTTP_STATUS} or {@link
 *       JutsuDriftSignal#EMPTY_RESPONSE} in the most recent {@code criticalLookback} events
 *       (default 5).
 *   <li>{@code DEGRADED} — total events in window ≥ {@code degradedThreshold} (default 5), or any
 *       {@link JutsuDriftSignal#SELECTOR_MISS} / {@link JutsuDriftSignal#UNKNOWN_TEMPLATE} present
 *       in the window.
 *   <li>{@code HEALTHY} — otherwise.
 * </ul>
 */
public final class JutsuDriftDetector {

    /** Default sliding-window size; tuned so a 6h scheduled probe with ~25 canaries fits. */
    public static final int DEFAULT_WINDOW_SIZE = 200;

    /** Default lookback for UNAVAILABLE evaluation. */
    public static final int DEFAULT_CRITICAL_LOOKBACK = 5;

    /** Default DEGRADED count threshold. */
    public static final int DEFAULT_DEGRADED_THRESHOLD = 5;

    private final int windowSize;
    private final int criticalLookback;
    private final int degradedThreshold;
    private final Clock clock;

    private final Object lock = new Object();
    private final Deque<JutsuDriftEvent> window = new LinkedList<>();
    private final EnumMap<JutsuDriftSignal, AtomicInteger> lifetimeBySignal =
            new EnumMap<>(JutsuDriftSignal.class);
    private final AtomicInteger lifetimeTotal = new AtomicInteger(0);

    public JutsuDriftDetector() {
        this(
                DEFAULT_WINDOW_SIZE,
                DEFAULT_CRITICAL_LOOKBACK,
                DEFAULT_DEGRADED_THRESHOLD,
                Clock.systemUTC());
    }

    public JutsuDriftDetector(
            int windowSize, int criticalLookback, int degradedThreshold, Clock clock) {
        if (windowSize <= 0) {
            throw new IllegalArgumentException("windowSize must be positive: " + windowSize);
        }
        if (criticalLookback <= 0 || criticalLookback > windowSize) {
            throw new IllegalArgumentException(
                    "criticalLookback must be in (0, windowSize]: " + criticalLookback);
        }
        if (degradedThreshold <= 0) {
            throw new IllegalArgumentException(
                    "degradedThreshold must be positive: " + degradedThreshold);
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.windowSize = windowSize;
        this.criticalLookback = criticalLookback;
        this.degradedThreshold = degradedThreshold;
        this.clock = clock;
        for (JutsuDriftSignal signal : JutsuDriftSignal.values()) {
            lifetimeBySignal.put(signal, new AtomicInteger(0));
        }
    }

    /**
     * Append an event to the window, evicting the oldest entry if at capacity. Updates lifetime
     * counters as a side effect. Never throws on null events — defensively logs nothing.
     */
    public void observe(JutsuDriftEvent event) {
        if (event == null) return;
        synchronized (lock) {
            window.addLast(event);
            while (window.size() > windowSize) {
                window.removeFirst();
            }
        }
        lifetimeBySignal.get(event.signal()).incrementAndGet();
        lifetimeTotal.incrementAndGet();
    }

    /** Capture a coherent snapshot under the deque lock. Cheap (≤ windowSize copy). */
    public JutsuDriftSnapshot snapshot() {
        Instant now = clock.instant();
        List<JutsuDriftEvent> recent;
        synchronized (lock) {
            recent = new ArrayList<>(window);
        }
        EnumMap<JutsuDriftSignal, Integer> bySignal = new EnumMap<>(JutsuDriftSignal.class);
        for (JutsuDriftEvent event : recent) {
            bySignal.merge(event.signal(), 1, Integer::sum);
        }
        JutsuDriftHealth health = computeHealth(recent, bySignal);
        return new JutsuDriftSnapshot(
                now, health, lifetimeTotal.get(), windowSize, recent.size(), bySignal, recent);
    }

    /** Reset the window and lifetime counters. Test/admin operation only. */
    public void reset() {
        synchronized (lock) {
            window.clear();
        }
        for (AtomicInteger count : lifetimeBySignal.values()) {
            count.set(0);
        }
        lifetimeTotal.set(0);
    }

    /** Lifetime count for a specific signal. Useful for spot-checks in tests. */
    public int lifetimeCount(JutsuDriftSignal signal) {
        return lifetimeBySignal.get(signal).get();
    }

    public int windowSize() {
        return windowSize;
    }

    public int criticalLookback() {
        return criticalLookback;
    }

    public int degradedThreshold() {
        return degradedThreshold;
    }

    private JutsuDriftHealth computeHealth(
            List<JutsuDriftEvent> recent, Map<JutsuDriftSignal, Integer> bySignal) {
        if (recent.isEmpty()) {
            return JutsuDriftHealth.HEALTHY;
        }
        int from = Math.max(0, recent.size() - criticalLookback);
        for (int i = from; i < recent.size(); i++) {
            JutsuDriftSignal signal = recent.get(i).signal();
            if (signal == JutsuDriftSignal.UNEXPECTED_HTTP_STATUS
                    || signal == JutsuDriftSignal.EMPTY_RESPONSE) {
                return JutsuDriftHealth.UNAVAILABLE;
            }
        }
        if (recent.size() >= degradedThreshold) {
            return JutsuDriftHealth.DEGRADED;
        }
        if (bySignal.getOrDefault(JutsuDriftSignal.SELECTOR_MISS, 0) > 0
                || bySignal.getOrDefault(JutsuDriftSignal.UNKNOWN_TEMPLATE, 0) > 0) {
            return JutsuDriftHealth.DEGRADED;
        }
        return JutsuDriftHealth.HEALTHY;
    }
}
