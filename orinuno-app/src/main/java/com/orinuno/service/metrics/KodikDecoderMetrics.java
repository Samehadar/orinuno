package com.orinuno.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Visibility for the {@link com.orinuno.service.KodikVideoDecoderService} pipeline.
 *
 * <p>Three counter families:
 *
 * <ul>
 *   <li>{@code orinuno.decoder.path{path=...}} — which decode path was taken: short-circuit (URL
 *       was already plain), cached-shift hit, brute-force discovery (with new shift), fallback
 *       (every shift failed).
 *   <li>{@code orinuno.decoder.shift{shift=...}} — distribution of ROT shift values that succeeded.
 *       Useful to spot the day Kodik changes the cipher (a sudden shift in the histogram).
 *   <li>{@code orinuno.decoder.quality{quality=...}} — distribution of quality buckets that {@code
 *       ParserService.selectBestQuality} ended up picking. Per ADR 0004, the day a {@code
 *       quality="1080"} bucket appears we re-evaluate the quality strategy.
 * </ul>
 *
 * <p>Cardinality is bounded: {@code path} is an enum, {@code shift} ranges 0..25 + {@code _none},
 * {@code quality} ranges over the discrete bucket strings Kodik emits (today: 240/360/480/720). All
 * fit comfortably under Prometheus' soft cardinality budget.
 */
@Slf4j
@Component
public class KodikDecoderMetrics {

    static final String PATH_METRIC = "orinuno.decoder.path";
    static final String SHIFT_METRIC = "orinuno.decoder.shift";
    static final String QUALITY_METRIC = "orinuno.decoder.quality";

    /** Stable enum of decode paths. Add new values here, not via free-form strings. */
    public enum DecodePath {
        SHORT_CIRCUIT_M3U8("short_circuit_m3u8"),
        SHORT_CIRCUIT_HTTP("short_circuit_http"),
        CACHED_SHIFT_HIT("cached_shift_hit"),
        BRUTE_FORCE_NEW_SHIFT("brute_force_new_shift"),
        FALLBACK_NO_SHIFT_WORKED("fallback_no_shift_worked");

        private final String tag;

        DecodePath(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public KodikDecoderMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void recordDecodePath(DecodePath path) {
        counter(PATH_METRIC, "Distribution of decoder paths taken", "path", path.tag()).increment();
    }

    public void recordShiftHit(int shift) {
        String tag = (shift < 0 || shift > 25) ? "_none" : Integer.toString(shift);
        counter(SHIFT_METRIC, "Distribution of successful ROT shift values", "shift", tag)
                .increment();
    }

    public void recordPickedQuality(String quality) {
        String tag = (quality == null || quality.isBlank()) ? "_none" : quality;
        counter(
                        QUALITY_METRIC,
                        "Distribution of quality buckets picked by selectBestQuality",
                        "quality",
                        tag)
                .increment();
    }

    private Counter counter(String name, String description, String tagKey, String tagValue) {
        String key = name + "|" + tagKey + "=" + tagValue;
        return counters.computeIfAbsent(
                key,
                k ->
                        Counter.builder(name)
                                .description(description)
                                .tags(Tags.of(tagKey, tagValue))
                                .register(meterRegistry));
    }
}
