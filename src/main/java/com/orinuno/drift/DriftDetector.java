package com.orinuno.drift;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Domain-neutral drift detector: compares JSON-shaped {@code Map<String, Object>} payloads against
 * the field set declared on a Jackson DTO and records any unknown keys as {@link DriftRecord}s.
 *
 * <p>Single low-level entrypoint — {@link #detect(Map, Class, String)} — and one convenience facade
 * — {@link #detectEnvelopeAndItems(Map, Class, Class)} — for the common {@code envelope.results[*]}
 * shape used by Kodik. Item sampling depth is configurable via {@link DriftSamplingProperties}.
 *
 * <p>Drift records are aggregated by {@code contextLabel}: repeated hits merge unknown-field sets
 * and increment {@code hitCount}. Each {@code detect*} call counts as one entry in {@link
 * #totalChecks} regardless of whether drift was found.
 */
@Slf4j
public class DriftDetector {

    private final DriftSamplingProperties properties;

    @Getter private final Map<String, DriftRecord> detectedDrifts = new ConcurrentHashMap<>();
    @Getter private final AtomicInteger totalChecks = new AtomicInteger(0);
    @Getter private final AtomicInteger totalDriftsDetected = new AtomicInteger(0);

    public DriftDetector(DriftSamplingProperties properties) {
        this.properties = properties == null ? DriftSamplingProperties.defaults() : properties;
    }

    /** Convenience: defaults (enabled, FIRST_N=10). */
    public DriftDetector() {
        this(DriftSamplingProperties.defaults());
    }

    /**
     * Compare the keys of {@code rawMap} to the JSON field names declared on {@code expectedType}
     * and record any unknown keys under {@code contextLabel}. No-op when the detector is disabled.
     */
    public void detect(Map<String, Object> rawMap, Class<?> expectedType, String contextLabel) {
        if (!properties.isEnabled() || rawMap == null) {
            return;
        }
        totalChecks.incrementAndGet();
        Set<String> known = DtoFieldExtractor.knownJsonFields(expectedType);
        Set<String> unknown = diff(rawMap.keySet(), known);
        if (!unknown.isEmpty()) {
            log.warn("Schema drift: unknown fields {} in {}", unknown, contextLabel);
            recordDrift(contextLabel, unknown);
        }
    }

    /**
     * Convenience facade for the {@code envelope { results: [...] }} pattern. Validates envelope
     * keys against {@code envelopeType} and item keys against {@code itemType}, with item sampling
     * controlled by {@link DriftSamplingProperties}.
     *
     * <p>Context labels default to the simple class names. Use {@link #detectEnvelopeAndItems(Map,
     * Class, Class, String, String)} when explicit labels are needed (e.g. parameterised envelope
     * like {@code KodikReferenceResponse<KodikGenreDto>}).
     */
    public <E> void detectEnvelopeAndItems(
            Map<String, Object> raw, Class<E> envelopeType, Class<?> itemType) {
        detectEnvelopeAndItems(
                raw,
                envelopeType,
                itemType,
                envelopeType.getSimpleName(),
                itemType.getSimpleName());
    }

    public <E> void detectEnvelopeAndItems(
            Map<String, Object> raw,
            Class<E> envelopeType,
            Class<?> itemType,
            String envelopeContext,
            String itemContext) {
        if (!properties.isEnabled() || raw == null) {
            return;
        }
        totalChecks.incrementAndGet();
        detectEnvelopeKeys(raw, envelopeType, envelopeContext);
        detectItemKeys(raw, itemType, itemContext);
    }

    private void detectEnvelopeKeys(
            Map<String, Object> raw, Class<?> envelopeType, String contextLabel) {
        Set<String> known = DtoFieldExtractor.knownJsonFields(envelopeType);
        Set<String> unknown = diff(raw.keySet(), known);
        if (!unknown.isEmpty()) {
            log.warn("Schema drift: unknown fields {} in {}", unknown, contextLabel);
            recordDrift(contextLabel, unknown);
        }
    }

    private void detectItemKeys(Map<String, Object> raw, Class<?> itemType, String contextLabel) {
        Object results = raw.get("results");
        if (results == null) {
            return;
        }
        if (!(results instanceof List<?> list)) {
            String shape = "results:" + results.getClass().getSimpleName();
            log.warn("Schema drift: results field is not a List ({}) in {}", shape, contextLabel);
            recordDrift("envelopeShapeViolation", Set.of(shape));
            return;
        }
        if (list.isEmpty()) {
            return;
        }
        int sample = sampleSize(list.size());
        if (sample == 0) {
            return;
        }
        Set<String> known = DtoFieldExtractor.knownJsonFields(itemType);
        Set<String> aggregateUnknown = new LinkedHashSet<>();
        for (int i = 0; i < sample; i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                for (Object k : map.keySet()) {
                    String key = k == null ? "null" : k.toString();
                    if (!known.contains(key)) {
                        aggregateUnknown.add(key);
                    }
                }
            }
        }
        if (!aggregateUnknown.isEmpty()) {
            log.warn(
                    "Schema drift: unknown fields {} in {} (sampled {} of {} items)",
                    aggregateUnknown,
                    contextLabel,
                    sample,
                    list.size());
            recordDrift(contextLabel, aggregateUnknown);
        }
    }

    /**
     * Number of items a caller should inspect given the configured {@link ItemSamplingMode} and
     * limit. Exposed so callers that apply their own per-item drift checks (for example {@code
     * KodikResponseMapper} sampling nested {@code material_data}) share the same sample depth.
     */
    public int sampleSize(int actualSize) {
        DriftSamplingProperties.ItemSampling cfg = properties.getItemSampling();
        return switch (cfg.getMode()) {
            case NONE -> 0;
            case FIRST_N -> Math.min(actualSize, Math.max(0, cfg.getLimit()));
            case ALL -> actualSize;
        };
    }

    private static Set<String> diff(Set<String> rawKeys, Set<String> known) {
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : rawKeys) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        return unknown;
    }

    private void recordDrift(String context, Set<String> unknownFields) {
        totalDriftsDetected.incrementAndGet();
        Instant now = Instant.now();
        detectedDrifts.merge(
                context,
                new DriftRecord(new LinkedHashSet<>(unknownFields), now, now, 1),
                (existing, incoming) -> {
                    Set<String> merged = new LinkedHashSet<>(existing.unknownFields());
                    merged.addAll(incoming.unknownFields());
                    return new DriftRecord(
                            merged, existing.firstSeen(), now, existing.hitCount() + 1);
                });
    }
}
