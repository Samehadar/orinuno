package com.orinuno.aksor.drift;

import java.time.Instant;
import java.util.Map;

/**
 * One drift signal emission. {@code context} is a small free-form map (animeId, hash, hostId, ...)
 * that helps operators correlate the event with the offending input. The detector defensively
 * copies it on construction so callers cannot mutate the snapshot after the fact.
 */
public record AksorDriftEvent(AksorDriftSignal signal, Map<String, String> context, Instant at) {

    public AksorDriftEvent {
        if (signal == null) {
            throw new IllegalArgumentException("signal is required");
        }
        if (at == null) {
            at = Instant.now();
        }
        context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static AksorDriftEvent of(AksorDriftSignal signal, Map<String, String> context) {
        return new AksorDriftEvent(signal, context, Instant.now());
    }
}
