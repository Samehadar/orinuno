package com.orinuno.service.requestlog;

import com.orinuno.model.ParseRequestStatus;
import com.orinuno.repository.ParseRequestRepository;
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

/**
 * Micrometer instrumentation for the parse-request log. Exposed via {@code GET
 * /actuator/prometheus}. Phase 2.5 hardening — without these gauges the Phase 2 live-run regression
 * (queue stuck PENDING because the shared scheduler thread was blocked) could only be diagnosed by
 * tailing logs. See {@code TECH_DEBT.md → TD-PR-5}.
 *
 * <p>Surface:
 *
 * <ul>
 *   <li>{@code orinuno_parse_requests{status="PENDING|RUNNING|DONE|FAILED"}} — current row count
 *       per status (gauge, polled lazily on each scrape).
 *   <li>{@code orinuno_parse_request_worker_tick_seconds} — wall-clock latency of {@code
 *       RequestWorker.tick()} (timer).
 *   <li>{@code orinuno_parse_request_processing_seconds} — wall-clock duration of a single
 *       parse-request lifecycle from claim to terminal status (timer, tagged {@code
 *       outcome="DONE|FAILED"}).
 *   <li>{@code orinuno_parse_requests_completed_total{outcome="DONE|FAILED"}} — total terminal
 *       transitions since start (counter).
 * </ul>
 */
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
            Gauge.builder("orinuno.parse.requests", repository, r -> safeCount(r, status))
                    .description("Number of orinuno parse requests in the given status")
                    .tags(Tags.of("status", status.name()))
                    .register(meterRegistry);
        }

        workerTickTimer =
                Timer.builder("orinuno.parse.request.worker.tick")
                        .description("Wall-clock latency of RequestWorker.tick() invocations")
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry);

        for (ParseRequestStatus terminal :
                new ParseRequestStatus[] {ParseRequestStatus.DONE, ParseRequestStatus.FAILED}) {
            processingTimers.put(
                    terminal,
                    Timer.builder("orinuno.parse.request.processing")
                            .description("Time from claim to terminal status")
                            .tags(Tags.of("outcome", terminal.name()))
                            .publishPercentiles(0.5, 0.95, 0.99)
                            .register(meterRegistry));
            completedCounters.put(
                    terminal,
                    Counter.builder("orinuno.parse.requests.completed")
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
