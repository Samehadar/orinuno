package com.orinuno.jutsu.drift;

import jakarta.annotation.Nullable;
import java.time.Instant;

/**
 * One drift observation. Reported by parsers and HTTP clients through {@link JutsuParserContext}
 * and consumed by {@link JutsuDriftDetector}.
 *
 * <p>{@link #source} is the originating subsystem (e.g., {@code "JutsuCatalogParser"} or {@code
 * "JutsuNoticeClient"}) — used to group events on the health endpoint and on dashboards. {@link
 * #detail} is a short human-readable description; {@link #selector} and {@link #fixtureRef} are
 * optional contextual hints that operators use when triaging.
 *
 * @param signal what kind of drift was observed
 * @param source originating subsystem; never null
 * @param detail human-readable description; never null but may be empty
 * @param timestamp when the event was observed; provided by the caller (not derived) so
 *     fixture-replay tests can pin a deterministic clock
 * @param selector failing CSS selector for {@link JutsuDriftSignal#SELECTOR_MISS}, may be null for
 *     other signals
 * @param fixtureRef optional URL or fixture name; helps operators reproduce
 */
public record JutsuDriftEvent(
        JutsuDriftSignal signal,
        String source,
        String detail,
        Instant timestamp,
        @Nullable String selector,
        @Nullable String fixtureRef) {

    public JutsuDriftEvent {
        if (signal == null) throw new IllegalArgumentException("signal must not be null");
        if (source == null) throw new IllegalArgumentException("source must not be null");
        if (detail == null) throw new IllegalArgumentException("detail must not be null");
        if (timestamp == null) throw new IllegalArgumentException("timestamp must not be null");
    }

    /** Convenience constructor without selector/fixture refs. */
    public static JutsuDriftEvent of(JutsuDriftSignal signal, String source, String detail) {
        return new JutsuDriftEvent(signal, source, detail, Instant.now(), null, null);
    }

    /** Convenience constructor for selector misses. */
    public static JutsuDriftEvent selectorMiss(String source, String selector, String detail) {
        return new JutsuDriftEvent(
                JutsuDriftSignal.SELECTOR_MISS, source, detail, Instant.now(), selector, null);
    }
}
