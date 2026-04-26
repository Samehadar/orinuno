package com.orinuno.service;

import com.orinuno.configuration.OrinunoProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Drives the long-running decoder maintenance jobs ({@link ParserService#refreshExpiredLinks()},
 * {@link ParserService#retryFailedDecodes()}) on the dedicated {@code orinuno-decoder-maint-*}
 * thread pool — completely isolated from the {@code orinuno-sched-*} pool that {@code
 * RequestWorker.tick()} runs on.
 *
 * <p>This was promoted out of {@link ParserService}'s {@code @Scheduled} annotations as part of the
 * Phase 2.5 hardening: under VPN-induced geo-block we observed a single {@code refreshExpiredLinks}
 * tick pin Spring's default scheduler thread for hours, leaving {@code RequestWorker.tick()} unable
 * to drain the parse-request queue. See {@code TECH_DEBT.md → TD-PR-5}.
 */
@Slf4j
@Component
public class DecoderMaintenanceScheduler {

    private final ParserService parserService;
    private final OrinunoProperties properties;
    private final TaskScheduler scheduler;

    private final List<ScheduledFuture<?>> handles = new ArrayList<>();

    public DecoderMaintenanceScheduler(
            ParserService parserService,
            OrinunoProperties properties,
            @Qualifier("decoderMaintenanceTaskScheduler") TaskScheduler scheduler) {
        this.parserService = parserService;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        Duration period = Duration.ofMillis(properties.getDecoder().getRefreshIntervalMs());
        Instant retryStart = Instant.now().plusMillis(retryInitialDelayMs());

        handles.add(
                scheduler.scheduleWithFixedDelay(
                        wrap("refreshExpiredLinks", parserService::refreshExpiredLinks), period));
        handles.add(
                scheduler.scheduleWithFixedDelay(
                        wrap("retryFailedDecodes", parserService::retryFailedDecodes),
                        retryStart,
                        period));

        log.info(
                "🧰 Decoder maintenance scheduler started (period={}ms, retry-initial-delay={}ms)",
                period.toMillis(),
                retryInitialDelayMs());
    }

    @PreDestroy
    void stop() {
        handles.forEach(handle -> handle.cancel(false));
        handles.clear();
    }

    private long retryInitialDelayMs() {
        long period = properties.getDecoder().getRefreshIntervalMs();
        return period / 2;
    }

    private Runnable wrap(String label, Runnable delegate) {
        return () -> {
            try {
                delegate.run();
            } catch (RuntimeException ex) {
                log.warn("⚠️ {} tick failed: {}", label, ex.toString());
            }
        };
    }
}
