package com.orinuno.jutsu.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Point-in-time view of the {@link JutsuDriftDetector} state. Returned by {@link
 * JutsuDriftDetector#snapshot()} so callers (the health endpoint, the ranker, dashboards) read a
 * consistent picture without holding any internal lock.
 *
 * @param capturedAt clock value at the moment the snapshot was taken
 * @param health coarse verdict; see {@link JutsuDriftHealth}
 * @param lifetimeEvents total events observed since the detector was created or last reset
 * @param windowSize configured cap of the recent-events deque
 * @param eventsInWindow current number of events in the deque (≤ windowSize)
 * @param eventsBySignalInWindow per-signal counts from the recent-events window
 * @param recentEvents an immutable copy of the recent-events deque, oldest-first
 */
public record JutsuDriftSnapshot(
        Instant capturedAt,
        JutsuDriftHealth health,
        int lifetimeEvents,
        int windowSize,
        int eventsInWindow,
        Map<JutsuDriftSignal, Integer> eventsBySignalInWindow,
        List<JutsuDriftEvent> recentEvents) {

    public JutsuDriftSnapshot {
        if (capturedAt == null) throw new IllegalArgumentException("capturedAt must not be null");
        if (health == null) throw new IllegalArgumentException("health must not be null");
        eventsBySignalInWindow =
                eventsBySignalInWindow == null ? Map.of() : Map.copyOf(eventsBySignalInWindow);
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }
}
