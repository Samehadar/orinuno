package com.orinuno.service.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

/**
 * Micrometer instrumentation for the on-demand Kodik calendar fetcher (IDEA-AP-5). All counters
 * share the same metric name with {@code outcome} as the only tag — keeps cardinality bounded at
 * five and aligns with the reference-cache observability convention.
 *
 * <p>Outcomes:
 *
 * <ul>
 *   <li>{@code success} — upstream returned 200 and the body parsed cleanly.
 *   <li>{@code not_modified} — upstream returned 304 and we re-served the cached payload.
 *   <li>{@code error} — upstream non-2xx, parse failure, oversize body, or transport timeout.
 *   <li>{@code disabled} — fetch was rejected because {@code orinuno.calendar.enabled=false}.
 * </ul>
 *
 * <p>{@code orinuno_kodik_calendar_entries} (summary) tracks the number of entries returned per
 * successful fetch — an early-warning signal when the dump shrinks unexpectedly.
 */
@Component
public class KodikCalendarMetrics {

    static final String FETCH_METRIC = "orinuno.kodik.calendar.fetch";
    static final String ENTRIES_METRIC = "orinuno.kodik.calendar.entries";

    private final Counter success;
    private final Counter notModified;
    private final Counter error;
    private final Counter disabled;
    private final DistributionSummary entriesSummary;

    public KodikCalendarMetrics(MeterRegistry registry) {
        this.success = build(registry, "success");
        this.notModified = build(registry, "not_modified");
        this.error = build(registry, "error");
        this.disabled = build(registry, "disabled");
        this.entriesSummary =
                DistributionSummary.builder(ENTRIES_METRIC)
                        .description("Entry count of the last successful calendar fetch")
                        .baseUnit("entries")
                        .register(registry);
    }

    public void fetchSuccess(int entryCount) {
        success.increment();
        entriesSummary.record(entryCount);
    }

    public void fetchNotModified() {
        notModified.increment();
    }

    public void fetchError() {
        error.increment();
    }

    public void fetchDisabled() {
        disabled.increment();
    }

    private static Counter build(MeterRegistry registry, String outcome) {
        return Counter.builder(FETCH_METRIC)
                .description("Outcome of a Kodik calendar dump fetch attempt")
                .tags(Tags.of("outcome", outcome))
                .register(registry);
    }
}
