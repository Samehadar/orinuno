package com.orinuno.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Knobs for {@link com.orinuno.jutsu.sync.JutsuCatalogSyncService} (ADR 0016 P1a).
 *
 * @param fullCrawlIntervalHours hours between full {@code JutsuClient.browseCatalog()} sweeps. ADR
 *     0016 §"jut.su (NEW) — JutsuCatalogSyncService" calls for "every 24–72h"; we floor at 24 to
 *     keep operators from accidentally hammering jut.su via a config typo.
 * @param noticeIntervalMinutes minutes between incremental notice-feed reads. Cheap (one POST per
 *     tick), so default is aggressive (5 min).
 * @param noticeLockTtlMinutes recovery window for a crashed notice-walk worker. After this many
 *     minutes the {@code jutsu_sync_state.notice_walk_in_progress} flag is considered stale and the
 *     next instance can re-acquire. Default 30 min — comfortably longer than a healthy tick.
 */
@ConfigurationProperties(prefix = "orinuno.jutsu.sync")
public record JutsuSyncProperties(
        long fullCrawlIntervalHours, long noticeIntervalMinutes, long noticeLockTtlMinutes) {

    public JutsuSyncProperties() {
        this(48, 5, 30);
    }

    @ConstructorBinding
    public JutsuSyncProperties(
            @DefaultValue("48") long fullCrawlIntervalHours,
            @DefaultValue("5") long noticeIntervalMinutes,
            @DefaultValue("30") long noticeLockTtlMinutes) {
        this.fullCrawlIntervalHours = fullCrawlIntervalHours;
        this.noticeIntervalMinutes = noticeIntervalMinutes;
        this.noticeLockTtlMinutes = noticeLockTtlMinutes;
    }

    public long effectiveFullCrawlIntervalHours() {
        return Math.max(24L, fullCrawlIntervalHours);
    }

    public long effectiveNoticeIntervalMinutes() {
        return Math.max(1L, noticeIntervalMinutes);
    }

    public long effectiveNoticeLockTtlMinutes() {
        return Math.max(1L, noticeLockTtlMinutes);
    }
}
