package com.orinuno.service.requestlog;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.model.ParseRequestStatus;
import com.orinuno.repository.ParseRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ParseRequestMetricsTest {

    @Mock private ParseRequestRepository repository;

    private MeterRegistry meterRegistry;
    private ParseRequestMetrics metrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        metrics = new ParseRequestMetrics(repository, meterRegistry);
        metrics.init();
    }

    @Test
    @DisplayName("registers status gauges, tick timer and completed counters")
    void registersExpectedMeters() {
        assertThat(meterRegistry.find("orinuno.parse.request.worker.tick").timer()).isNotNull();
        assertThat(
                        meterRegistry
                                .find("orinuno.parse.request.processing")
                                .tags(Tags.of("outcome", "DONE"))
                                .timer())
                .isNotNull();
        assertThat(
                        meterRegistry
                                .find("orinuno.parse.request.processing")
                                .tags(Tags.of("outcome", "FAILED"))
                                .timer())
                .isNotNull();
        assertThat(
                        meterRegistry
                                .find("orinuno.parse.requests.completed")
                                .tags(Tags.of("outcome", "DONE"))
                                .counter())
                .isNotNull();

        for (ParseRequestStatus status : ParseRequestStatus.values()) {
            assertThat(
                            meterRegistry
                                    .find("orinuno.parse.requests")
                                    .tags(Tags.of("status", status.name()))
                                    .gauge())
                    .as("gauge for status=%s", status)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("status gauge polls repository.countByStatus on each scrape")
    void gaugeRefreshesFromRepository() {
        Mockito.when(repository.countByStatus(ParseRequestStatus.PENDING)).thenReturn(7L);

        double value =
                meterRegistry
                        .find("orinuno.parse.requests")
                        .tags(Tags.of("status", "PENDING"))
                        .gauge()
                        .value();

        assertThat(value).isEqualTo(7.0);
    }

    @Test
    @DisplayName("recordCompletion increments DONE counter and writes to processing timer")
    void recordCompletionWritesDoneStats() {
        long start = System.nanoTime() - 1_500_000L; // ~1.5ms ago
        metrics.recordCompletion(ParseRequestStatus.DONE, start);

        double count =
                meterRegistry
                        .find("orinuno.parse.requests.completed")
                        .tags(Tags.of("outcome", "DONE"))
                        .counter()
                        .count();
        assertThat(count).isEqualTo(1.0);

        long timerCount =
                meterRegistry
                        .find("orinuno.parse.request.processing")
                        .tags(Tags.of("outcome", "DONE"))
                        .timer()
                        .count();
        assertThat(timerCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("recordCompletion is a no-op for non-terminal statuses")
    void recordCompletionIgnoresPendingAndRunning() {
        metrics.recordCompletion(ParseRequestStatus.PENDING, System.nanoTime());
        metrics.recordCompletion(ParseRequestStatus.RUNNING, System.nanoTime());

        double done =
                meterRegistry
                        .find("orinuno.parse.requests.completed")
                        .tags(Tags.of("outcome", "DONE"))
                        .counter()
                        .count();
        double failed =
                meterRegistry
                        .find("orinuno.parse.requests.completed")
                        .tags(Tags.of("outcome", "FAILED"))
                        .counter()
                        .count();
        assertThat(done).isZero();
        assertThat(failed).isZero();
    }
}
