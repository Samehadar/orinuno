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
    static final String OUTCOME_METRIC = "orinuno.decoder.outcome";
    static final String UPSTREAM_ERROR_METRIC = "orinuno.decoder.upstream_error";

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

    /**
     * Stable enum of decoder outcomes per call. DECODE-7 (not-found classification).
     *
     * <p>{@link #SUCCESS} — at least one URL decoded.<br>
     * {@link #EMPTY_LINKS} — Kodik responded 200 with parseable JSON but no {@code links} block (or
     * the block was empty). Variant has no playable URL right now; do NOT keep retrying forever.
     * <br>
     * {@link #GEO_BLOCKED} — every decoded URL routed to Kodik's geo-block edge proxy. Likely
     * caller is outside CIS or proxy pool is exhausted.<br>
     * {@link #UPSTREAM_ERROR} — POST returned 4xx/5xx; see {@link #recordUpstreamError(int,
     * String)} for the per-status / per-error-class breakdown.
     */
    public enum DecodeOutcome {
        SUCCESS("success"),
        EMPTY_LINKS("empty_links"),
        GEO_BLOCKED("geo_blocked"),
        UPSTREAM_ERROR("upstream_error");

        private final String tag;

        DecodeOutcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    /**
     * Coarse classes for upstream Kodik errors. DECODE-7 (500-handling).
     *
     * <p>Kodik uses HTTP 500 for application errors; the body string is what matters. We map known
     * Russian error strings to a small enum so dashboards can alert on each class without unbounded
     * cardinality.
     */
    public enum UpstreamErrorClass {
        TOKEN_INVALID("token_invalid"),
        SIGNED_PARAMS_STALE("signed_params_stale"),
        MISSING_SEARCH_PARAM("missing_search_param"),
        WRONG_TYPE("wrong_type"),
        OTHER("other");

        private final String tag;

        UpstreamErrorClass(String tag) {
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

    public void recordOutcome(DecodeOutcome outcome) {
        counter(
                        OUTCOME_METRIC,
                        "Per-call decoder outcome (DECODE-7 not-found classification)",
                        "outcome",
                        outcome.tag())
                .increment();
    }

    public void recordUpstreamError(int httpStatus, UpstreamErrorClass errorClass) {
        counter(
                        UPSTREAM_ERROR_METRIC,
                        "Decoder upstream errors broken down by HTTP status and parsed body class"
                                + " (DECODE-7 500-handling)",
                        Tags.of("status", Integer.toString(httpStatus), "class", errorClass.tag()))
                .increment();
    }

    private Counter counter(String name, String description, String tagKey, String tagValue) {
        return counter(name, description, Tags.of(tagKey, tagValue));
    }

    private Counter counter(String name, String description, Tags tags) {
        String key = name + "|" + tags;
        return counters.computeIfAbsent(
                key,
                k ->
                        Counter.builder(name)
                                .description(description)
                                .tags(tags)
                                .register(meterRegistry));
    }
}
