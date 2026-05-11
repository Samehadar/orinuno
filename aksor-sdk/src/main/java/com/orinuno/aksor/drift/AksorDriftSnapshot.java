package com.orinuno.aksor.drift;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of {@link AksorDriftDetector} state at one instant: total count per {@link
 * AksorDriftSignal}, plus the most recent N events for inspection.
 */
public record AksorDriftSnapshot(Map<AksorDriftSignal, Long> counts, List<AksorDriftEvent> recent) {

    public AksorDriftSnapshot {
        EnumMap<AksorDriftSignal, Long> filled = new EnumMap<>(AksorDriftSignal.class);
        for (AksorDriftSignal s : AksorDriftSignal.values()) {
            filled.put(s, counts == null ? 0L : counts.getOrDefault(s, 0L));
        }
        counts = Map.copyOf(filled);
        recent = recent == null ? List.of() : List.copyOf(recent);
    }

    public long count(AksorDriftSignal signal) {
        return counts.getOrDefault(signal, 0L);
    }

    public long total() {
        return counts.values().stream().mapToLong(Long::longValue).sum();
    }

    public boolean isClean() {
        return total() == 0;
    }
}
