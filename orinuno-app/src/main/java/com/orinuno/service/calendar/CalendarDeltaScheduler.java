package com.orinuno.service.calendar;

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
 * CAL-6 — periodically wakes {@link CalendarDeltaWatcher#runOnce()} on the dedicated
 * decoder-maintenance pool. Both the calendar fetch and the MyBatis upserts are blocking; running
 * them on the maintenance pool keeps Spring's default scheduler free for other lightweight work.
 *
 * <p>Disabled by default ({@code orinuno.calendar.delta-watcher.enabled=false}); enable after
 * applying the CAL-6 Liquibase migration.
 */
@Slf4j
@Component
public class CalendarDeltaScheduler {

    private final CalendarDeltaWatcher watcher;
    private final OrinunoProperties properties;
    private final TaskScheduler scheduler;

    private ScheduledFuture<?> handle;

    public CalendarDeltaScheduler(
            CalendarDeltaWatcher watcher,
            OrinunoProperties properties,
            @Qualifier("decoderMaintenanceTaskScheduler") TaskScheduler scheduler) {
        this.watcher = watcher;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        OrinunoProperties.CalendarProperties.DeltaWatcherProperties cfg =
                properties.getCalendar().getDeltaWatcher();
        if (!properties.getCalendar().isEnabled() || !cfg.isEnabled()) {
            log.info(
                    "📅 CAL-6: delta watcher disabled (calendar.enabled={},"
                            + " delta-watcher.enabled={})",
                    properties.getCalendar().isEnabled(),
                    cfg.isEnabled());
            return;
        }
        Duration interval = Duration.ofMinutes(Math.max(1, cfg.getPollIntervalMinutes()));
        Instant first = Instant.now().plusSeconds(Math.max(0, cfg.getInitialDelaySeconds()));
        handle =
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                int events = watcher.runOnce();
                                if (events > 0) {
                                    log.info(
                                            "📅 CAL-6: watcher tick wrote {} outbox event(s)",
                                            events);
                                }
                            } catch (RuntimeException ex) {
                                log.warn("⚠️ CAL-6: watcher tick failed: {}", ex.toString());
                            }
                        },
                        first,
                        interval);
        log.info(
                "📅 CAL-6: delta watcher scheduled (interval={}min, initial-delay={}s)",
                interval.toMinutes(),
                cfg.getInitialDelaySeconds());
    }

    @PreDestroy
    void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }
}
