package com.orinuno.jutsu.sync;

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
 * ARCH-0016 P1a Step 2 — periodically wakes {@link JutsuCatalogSyncService#runFullCrawlOnce(int)}
 * on the dedicated decoder-maintenance pool so the catalog full-crawl never starves the other
 * scheduled jobs (calendar delta watcher, parse-request worker, etc.) when it walks 30 pages back-
 * to-back at 1 RPS.
 *
 * <p>Disabled by default ({@code orinuno.providers.jutsu.sync.enabled=false} and {@code
 * orinuno.providers.jutsu.sync.full-crawl.enabled=false}); flip both flags after applying the P1a
 * Liquibase migrations to start mirroring the catalog locally.
 */
@Slf4j
@Component
public class JutsuCatalogSyncScheduler {

    private final JutsuCatalogSyncService syncService;
    private final OrinunoProperties properties;
    private final TaskScheduler scheduler;

    private ScheduledFuture<?> handle;

    public JutsuCatalogSyncScheduler(
            JutsuCatalogSyncService syncService,
            OrinunoProperties properties,
            @Qualifier("decoderMaintenanceTaskScheduler") TaskScheduler scheduler) {
        this.syncService = syncService;
        this.properties = properties;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        OrinunoProperties.JutsuProperties.SyncProperties cfg =
                properties.getProviders().getJutsu().getSync();
        OrinunoProperties.JutsuProperties.SyncProperties.FullCrawlProperties fc =
                cfg.getFullCrawl();
        if (!cfg.isEnabled() || !fc.isEnabled()) {
            log.info(
                    "jutsu-sync: scheduler disabled (sync.enabled={}, full-crawl.enabled={})",
                    cfg.isEnabled(),
                    fc.isEnabled());
            return;
        }
        Duration interval = Duration.ofHours(Math.max(1, fc.getIntervalHours()));
        Instant first = Instant.now().plusSeconds(Math.max(0, fc.getInitialDelaySeconds()));
        handle =
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                JutsuCatalogSyncService.FullCrawlResult result =
                                        syncService.runFullCrawlOnce(fc.getMaxPagesPerTick());
                                if (!result.wasSuccessful()) {
                                    log.warn(
                                            "jutsu-sync: full-crawl tick finished with error: {}",
                                            result.describe());
                                }
                            } catch (RuntimeException ex) {
                                log.warn(
                                        "jutsu-sync: full-crawl tick raised unexpectedly: {}",
                                        ex.toString());
                            }
                        },
                        first,
                        interval);
        log.info(
                "jutsu-sync: full-crawl scheduled (interval={}h, initialDelay={}s,"
                        + " maxPagesPerTick={})",
                interval.toHours(),
                fc.getInitialDelaySeconds(),
                fc.getMaxPagesPerTick());
    }

    @PreDestroy
    void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }
}
