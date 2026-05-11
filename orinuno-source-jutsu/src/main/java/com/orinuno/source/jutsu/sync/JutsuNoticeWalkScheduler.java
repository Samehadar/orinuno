package com.orinuno.source.jutsu.sync;

import com.orinuno.source.jutsu.JutsuSourceProperties;
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
 * ARCH-0016 P1a Step 2.B — periodically wakes {@link JutsuCatalogSyncService#runNoticeWalkOnce(int,
 * int)} on the dedicated decoder-maintenance pool. Lives next to {@link JutsuCatalogSyncScheduler}
 * but cadence is much faster (default 15min vs 24h) because the notice walker is cheap on quiet
 * windows — one homepage GET + one POST is the minimum cost per tick.
 *
 * <p>Disabled by default ({@code orinuno.providers.jutsu.sync.notice-walk.enabled=false}); enable
 * after {@link JutsuCatalogSyncScheduler} has done at least one full crawl so the cache is warm
 * enough that "previously-unseen slug" triggers are actually rare.
 */
@Slf4j
@Component
public class JutsuNoticeWalkScheduler {

    private final JutsuCatalogSyncService syncService;
    private final JutsuSourceProperties props;
    private final TaskScheduler scheduler;

    private ScheduledFuture<?> handle;

    public JutsuNoticeWalkScheduler(
            JutsuCatalogSyncService syncService,
            JutsuSourceProperties props,
            @Qualifier("decoderMaintenanceTaskScheduler") TaskScheduler scheduler) {
        this.syncService = syncService;
        this.props = props;
        this.scheduler = scheduler;
    }

    @PostConstruct
    void start() {
        JutsuSourceProperties.SyncProperties cfg = props.getSync();
        JutsuSourceProperties.SyncProperties.NoticeWalkProperties nw = cfg.getNoticeWalk();
        if (!cfg.isEnabled() || !nw.isEnabled()) {
            log.info(
                    "jutsu-sync: notice-walk scheduler disabled (sync.enabled={},"
                            + " notice-walk.enabled={})",
                    cfg.isEnabled(),
                    nw.isEnabled());
            return;
        }
        Duration interval = Duration.ofMinutes(Math.max(1, nw.getIntervalMinutes()));
        Instant first = Instant.now().plusSeconds(Math.max(0, nw.getInitialDelaySeconds()));
        handle =
                scheduler.scheduleWithFixedDelay(
                        () -> {
                            try {
                                JutsuCatalogSyncService.NoticeWalkResult result =
                                        syncService.runNoticeWalkOnce(
                                                nw.getMaxFeedsPerTick(),
                                                nw.getMaxInfoFetchesPerTick());
                                if (!result.wasSuccessful()) {
                                    log.warn(
                                            "jutsu-sync: notice-walk tick finished with error: {}",
                                            result.describe());
                                }
                            } catch (RuntimeException ex) {
                                log.warn(
                                        "jutsu-sync: notice-walk tick raised unexpectedly: {}",
                                        ex.toString());
                            }
                        },
                        first,
                        interval);
        log.info(
                "jutsu-sync: notice-walk scheduled (interval={}min, initialDelay={}s,"
                        + " maxFeedsPerTick={}, fetchInfoOnDiscovery={},"
                        + " maxInfoFetchesPerTick={})",
                interval.toMinutes(),
                nw.getInitialDelaySeconds(),
                nw.getMaxFeedsPerTick(),
                nw.isFetchInfoOnDiscovery(),
                nw.getMaxInfoFetchesPerTick());
    }

    @PreDestroy
    void stop() {
        if (handle != null) {
            handle.cancel(false);
            handle = null;
        }
    }
}
