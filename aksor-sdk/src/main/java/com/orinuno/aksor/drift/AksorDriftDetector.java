package com.orinuno.aksor.drift;

import jakarta.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;

/**
 * Thread-safe collector for {@link AksorDriftEvent}s. Hosts and the API client {@code record(...)}
 * a signal whenever they trip an anomaly; callers poll {@link #snapshot()} for counts + recent
 * events. The collector itself is purely in-memory and stateless across JVM restarts — wrap in
 * Micrometer / dashboard plumbing at the orinuno-app layer if you need persistence.
 *
 * <p>Recent-event buffer is a bounded ring (default 128) so a runaway broken source does not OOM
 * the host process. Counts are accumulated via {@link LongAdder} (cheaper under contention than
 * AtomicLong) so they never roll back on overflow within practical lifetimes.
 *
 * <p>The default no-op detector returned by {@link #disabled()} swallows every signal — used when
 * the caller does not opt in via {@link com.orinuno.aksor.AksorClient.Builder#driftDetector}. Hot
 * paths can short-circuit by checking {@link #isEnabled()} before building heavy context maps.
 */
@Slf4j
public final class AksorDriftDetector {

    static final int DEFAULT_RING_SIZE = 128;

    private final boolean enabled;
    private final int ringSize;
    private final EnumMap<AksorDriftSignal, LongAdder> counts;
    private final Deque<AksorDriftEvent> recent;
    private final AtomicLong dropped;

    public AksorDriftDetector() {
        this(true, DEFAULT_RING_SIZE);
    }

    public AksorDriftDetector(int ringSize) {
        this(true, ringSize);
    }

    private AksorDriftDetector(boolean enabled, int ringSize) {
        if (ringSize < 1) {
            throw new IllegalArgumentException("ringSize must be >= 1");
        }
        this.enabled = enabled;
        this.ringSize = ringSize;
        EnumMap<AksorDriftSignal, LongAdder> map = new EnumMap<>(AksorDriftSignal.class);
        for (AksorDriftSignal s : AksorDriftSignal.values()) {
            map.put(s, new LongAdder());
        }
        this.counts = map;
        this.recent = new ArrayDeque<>(ringSize);
        this.dropped = new AtomicLong();
    }

    /** No-op singleton — every {@code record} call short-circuits. Used as the default. */
    public static AksorDriftDetector disabled() {
        return new AksorDriftDetector(false, 1);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void record(AksorDriftSignal signal) {
        record(signal, Map.of());
    }

    public void record(AksorDriftSignal signal, @Nullable Map<String, String> context) {
        if (!enabled || signal == null) {
            return;
        }
        counts.get(signal).increment();
        AksorDriftEvent event = AksorDriftEvent.of(signal, context);
        synchronized (recent) {
            if (recent.size() >= ringSize) {
                recent.pollFirst();
                dropped.incrementAndGet();
            }
            recent.addLast(event);
        }
        log.debug("Aksor drift signal: {} {}", signal, event.context());
    }

    public AksorDriftSnapshot snapshot() {
        Map<AksorDriftSignal, Long> snap = new EnumMap<>(AksorDriftSignal.class);
        for (Map.Entry<AksorDriftSignal, LongAdder> e : counts.entrySet()) {
            snap.put(e.getKey(), e.getValue().sum());
        }
        List<AksorDriftEvent> recentCopy;
        synchronized (recent) {
            recentCopy = new ArrayList<>(recent);
        }
        return new AksorDriftSnapshot(snap, recentCopy);
    }

    /** How many events were evicted from the ring buffer to make room for newer ones. */
    public long droppedFromRing() {
        return dropped.get();
    }

    public void reset() {
        for (LongAdder a : counts.values()) {
            a.reset();
        }
        synchronized (recent) {
            recent.clear();
        }
        dropped.set(0);
    }
}
