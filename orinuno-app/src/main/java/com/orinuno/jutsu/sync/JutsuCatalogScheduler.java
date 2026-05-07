package com.orinuno.jutsu.sync;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Thin {@code @Scheduled} façade over {@link JutsuCatalogSyncService}. Kept separate so unit tests
 * can drive the service directly without booting the scheduler. Runs on the dedicated {@code
 * jutsuSyncTaskScheduler} pool wired in {@link
 * com.orinuno.jutsu.configuration.JutsuCatalogSyncConfiguration} — we never share a pool with the
 * parse-request worker (TD-PR-5 lesson).
 */
@Slf4j
public class JutsuCatalogScheduler {

    private final JutsuCatalogSyncService syncService;

    public JutsuCatalogScheduler(JutsuCatalogSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(
            fixedDelayString =
                    "#{${orinuno.jutsu.sync.full-crawl-interval-hours:48} * 60 * 60 * 1000}",
            initialDelayString = "${orinuno.jutsu.sync.full-crawl-initial-delay-ms:60000}",
            scheduler = "jutsuSyncTaskScheduler")
    public void fullCrawl() {
        try {
            syncService.fullCrawl();
        } catch (RuntimeException ex) {
            // Belt + suspenders: the service already swallows, but a misconfigured bean wiring
            // (e.g. a missing repository) could still bubble. Never crash the scheduler thread.
            log.error("❌ jut.su scheduled full crawl threw", ex);
        }
    }

    @Scheduled(
            fixedDelayString = "#{${orinuno.jutsu.sync.notice-interval-minutes:5} * 60 * 1000}",
            initialDelayString = "${orinuno.jutsu.sync.notice-initial-delay-ms:30000}",
            scheduler = "jutsuSyncTaskScheduler")
    public void noticeIncremental() {
        try {
            syncService.noticeIncremental();
        } catch (RuntimeException ex) {
            log.error("❌ jut.su scheduled notice incremental threw", ex);
        }
    }
}
