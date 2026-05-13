package com.orinuno.source.kodik.service.dumps;

import com.orinuno.source.kodik.configuration.KodikDumpsProperties;
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
 * <p>No-op when {@code orinuno.source-kodik.dumps.enabled=false}. The interval is read once at
 * startup; restart the app to change it. Restored from orinuno-app under ADR 0021 §D5; the {@code
 * decoderMaintenanceTaskScheduler} qualifier matches the bean orinuno-source-kodik already
 * publishes for the decoder maintenance jobs.
 */
@Slf4j
@Component
public class DumpScheduler {

    private final KodikDumpService dumpService;
    private final KodikDumpsProperties properties;
    private final TaskScheduler scheduler;

    private ScheduledFuture<?> handle;

    public DumpScheduler(
            KodikDumpService dumpService,
            KodikDumpsProperties properties,
            @Qualifier("decoderMaintenanceTaskScheduler") TaskScheduler scheduler) {
        this.dumpService = dumpService;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        if (!properties.isEnabled()) {
            log.info("📦 Dump scheduler disabled (orinuno.source-kodik.dumps.enabled=false)");
            return;
        }
        Duration interval = Duration.ofMinutes(Math.max(1, properties.getPollIntervalMinutes()));
        Instant first = Instant.now().plusSeconds(Math.max(0, properties.getInitialDelaySeconds()));
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
                properties.getInitialDelaySeconds());
    }

    @PreDestroy
    void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }
}
