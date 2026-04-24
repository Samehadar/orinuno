package com.orinuno.drift;

import java.time.Instant;
import java.util.Set;

/**
 * Aggregated record of unknown JSON fields seen for a given context (a DTO or composed label).
 * Aggregation merges the union of unknown fields across hits so a single record represents the full
 * known set of drift over time, not just the most recent occurrence.
 */
public record DriftRecord(
        Set<String> unknownFields, Instant firstSeen, Instant lastSeen, int hitCount) {}
