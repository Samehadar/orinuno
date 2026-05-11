package com.orinuno.source.jutsu.dto;

import com.orinuno.jutsu.drift.JutsuDriftEvent;
import com.orinuno.jutsu.drift.JutsuDriftSignal;
import com.orinuno.jutsu.drift.JutsuDriftSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** REST projection of {@link JutsuDriftSnapshot}. */
@Schema(description = "Point-in-time snapshot of jut.su SDK drift state.")
public record JutsuDriftSnapshotDto(
        @Schema(description = "When this snapshot was captured") Instant capturedAt,
        @Schema(
                        description =
                                "HEALTHY when the recent window is empty, DEGRADED when one or"
                                        + " more drift events have been observed, UNAVAILABLE when"
                                        + " the detector itself is offline")
                String health,
        @Schema(description = "Total drift events ever observed by this process")
                int lifetimeEvents,
        @Schema(description = "Cap on the recent-events window") int windowSize,
        @Schema(description = "Current events in the window") int eventsInWindow,
        @Schema(description = "Counts by signal type in the window") Map<String, Integer> bySignal,
        @Schema(description = "Recent events oldest-first") List<JutsuDriftEventDto> recentEvents) {

    public static JutsuDriftSnapshotDto from(JutsuDriftSnapshot s) {
        Map<String, Integer> bySignal =
                s.eventsBySignalInWindow().entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        e -> e.getKey().name(),
                                        Map.Entry::getValue,
                                        (a, b) -> a,
                                        java.util.LinkedHashMap::new));
        return new JutsuDriftSnapshotDto(
                s.capturedAt(),
                s.health().name(),
                s.lifetimeEvents(),
                s.windowSize(),
                s.eventsInWindow(),
                bySignal,
                s.recentEvents().stream().map(JutsuDriftEventDto::from).toList());
    }

    @Schema(description = "One observed drift event.")
    public record JutsuDriftEventDto(
            @Schema(description = "Drift signal type, see JutsuDriftSignal enum") String signal,
            @Schema(description = "Source label (parser / client class name)") String source,
            @Schema(description = "Free-form diagnostic message") String detail,
            Instant timestamp,
            @Schema(nullable = true) @Nullable String selector,
            @Schema(nullable = true) @Nullable String fixtureRef) {

        public static JutsuDriftEventDto from(JutsuDriftEvent e) {
            return new JutsuDriftEventDto(
                    e.signal().name(),
                    e.source(),
                    e.detail(),
                    e.timestamp(),
                    e.selector(),
                    e.fixtureRef());
        }
    }

    @Schema(description = "Set of all known signal codes for client-side switch statements.")
    public static List<String> allSignals() {
        return java.util.Arrays.stream(JutsuDriftSignal.values()).map(Enum::name).toList();
    }
}
