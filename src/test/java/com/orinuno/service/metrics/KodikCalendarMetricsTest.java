package com.orinuno.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikCalendarMetricsTest {

    private SimpleMeterRegistry registry;
    private KodikCalendarMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new KodikCalendarMetrics(registry);
    }

    @Test
    @DisplayName("Each outcome increments only its own counter")
    void outcomesAreSeparate() {
        metrics.fetchSuccess(42);
        metrics.fetchNotModified();
        metrics.fetchError();
        metrics.fetchDisabled();
        metrics.fetchSuccess(7);

        assertThat(counter("success")).isEqualTo(2.0);
        assertThat(counter("not_modified")).isEqualTo(1.0);
        assertThat(counter("error")).isEqualTo(1.0);
        assertThat(counter("disabled")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Entries summary records the entry count of each successful fetch")
    void entriesSummary() {
        metrics.fetchSuccess(100);
        metrics.fetchSuccess(120);

        assertThat(registry.find(KodikCalendarMetrics.ENTRIES_METRIC).summary().count())
                .isEqualTo(2);
        assertThat(registry.find(KodikCalendarMetrics.ENTRIES_METRIC).summary().totalAmount())
                .isEqualTo(220.0);
    }

    private double counter(String outcome) {
        return registry.find(KodikCalendarMetrics.FETCH_METRIC)
                .tags(Tags.of("outcome", outcome))
                .counter()
                .count();
    }
}
