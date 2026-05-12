/*
 * ParseRequestMetrics — ADR 0021 §D-prep.
 *
 * Micrometer instrumentation for source-kodik's parse-request queue.
 * Ported from orinuno-app/.../requestlog/ — metric names renamed from
 * orinuno.* to orinuno.source.kodik.* so the source-kodik instance
 * publishes its own time series instead of colliding with orinuno-app's
 * actuator surface (both are wired to /actuator/prometheus on different
 * ports in the full-split topology).
 */
package com.orinuno.source.kodik.service.requestlog;

import com.orinuno.source.kodik.model.ParseRequestStatus;
import com.orinuno.source.kodik.repository.ParseRequestRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.EnumMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ParseRequestMetrics {

    private final ParseRequestRepository repository;
    private final MeterRegistry meterRegistry;

    private Timer workerTickTimer;
    private final Map<ParseRequestStatus, Timer> processingTimers =
            new EnumMap<>(ParseRequestStatus.class);
    private final Map<ParseRequestStatus, Counter> completedCounters =
            new EnumMap<>(ParseRequestStatus.class);

    public ParseRequestMetrics(ParseRequestRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        for (ParseRequestStatus status : ParseRequestStatus.values()) {
            Gauge.builder("orinuno.source.kodik.parse.requests", repository, r -> safeCount(r, status))
                    .description("Number of source-kodik parse requests in the given status")
                    .tags(Tags.of("status", status.name()))
                    .register(meterRegistry);
        }

        workerTickTimer =
                Timer.builder("orinuno.source.kodik.parse.request.worker.tick")
                        .description("Wall-clock latency of RequestWorker.tick() invocations")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry);

        for (ParseRequestStatus terminal :
                new ParseRequestStatus[] {ParseRequestStatus.DONE, ParseRequestStatus.FAILED}) {
            processingTimers.put(
                    terminal,
                    Timer.builder("orinuno.source.kodik.parse.request.processing")
                            .description("Time from claim to terminal status")
                            .tags(Tags.of("outcome", terminal.name()))
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
            completedCounters.put(
                    terminal,
                    Counter.builder("orinuno.source.kodik.parse.requests.completed")
                            .description("Total parse requests reaching a terminal status")
                            .tags(Tags.of("outcome", terminal.name()))
                            .register(meterRegistry));
        }
    }

    public Timer workerTickTimer() {
        return workerTickTimer;
    }

    public void recordCompletion(ParseRequestStatus terminal, long startNanos) {
        long elapsedNanos = System.nanoTime() - startNanos;
        Timer timer = processingTimers.get(terminal);
        if (timer != null) {
            timer.record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        Counter counter = completedCounters.get(terminal);
        if (counter != null) {
            counter.increment();
        }
    }

    private static double safeCount(ParseRequestRepository repository, ParseRequestStatus status) {
        try {
            return (double) repository.countByStatus(status);
        } catch (RuntimeException ex) {
            log.debug("countByStatus({}) failed during metric scrape: {}", status, ex.toString());
            return Double.NaN;
        }
    }
}
