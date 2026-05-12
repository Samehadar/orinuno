package com.orinuno.source.kodik.service.calendar;

import com.orinuno.source.kodik.configuration.KodikCalendarProperties;
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
    private final KodikCalendarProperties calendarProperties;
    private final TaskScheduler scheduler;

    private ScheduledFuture<?> handle;

    public CalendarDeltaScheduler(
            CalendarDeltaWatcher watcher,
            KodikCalendarProperties calendarProperties,
            @Qualifier("decoderMaintenanceTaskScheduler") TaskScheduler scheduler) {
        this.watcher = watcher;
        this.calendarProperties = calendarProperties;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        KodikCalendarProperties.DeltaWatcherProperties cfg =
                calendarProperties.getDeltaWatcher();
        if (!calendarProperties.isEnabled() || !cfg.isEnabled()) {
            log.info(
                    "📅 CAL-6: delta watcher disabled (calendar.enabled={},"
                            + " delta-watcher.enabled={})",
                    calendarProperties.isEnabled(),
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
