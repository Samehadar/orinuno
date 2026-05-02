package com.orinuno.service.dumps;

import com.orinuno.configuration.OrinunoProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * DUMP-1 — Wraps {@link KodikDumpService#pollAll()} on the dedicated decoder-maintenance thread
 * pool, so a slow Kodik response (~30s timeout) never blocks Spring's default scheduler.
 *
 * <p>No-op when {@code orinuno.dumps.enabled=false}. The interval is read once at startup; restart
 * the app to change it.
 */
@Slf4j
@Component
public class DumpScheduler {

    private final KodikDumpService dumpService;
    private final OrinunoProperties properties;
    private final TaskScheduler scheduler;

    private ScheduledFuture<?> handle;

    public DumpScheduler(
            KodikDumpService dumpService,
            OrinunoProperties properties,
            @Qualifier("decoderMaintenanceTaskScheduler") TaskScheduler scheduler) {
        this.dumpService = dumpService;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        if (!properties.getDumps().isEnabled()) {
            log.info("📦 Dump scheduler disabled (orinuno.dumps.enabled=false)");
            return;
        }
        Duration interval =
                Duration.ofMinutes(Math.max(1, properties.getDumps().getPollIntervalMinutes()));
        Instant first =
                Instant.now()
                        .plusSeconds(Math.max(0, properties.getDumps().getInitialDelaySeconds()));
        handle =
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                dumpService.pollAll();
                            } catch (RuntimeException ex) {
                                log.warn("⚠️ Dump poll tick failed: {}", ex.toString());
                            }
                        },
                        first,
                        interval);
        log.info(
                "📦 Dump scheduler started (interval={}min, initial-delay={}s)",
                interval.toMinutes(),
                properties.getDumps().getInitialDelaySeconds());
    }

    @PreDestroy
    void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }
}
