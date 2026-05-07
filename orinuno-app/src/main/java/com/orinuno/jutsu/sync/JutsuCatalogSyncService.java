package com.orinuno.jutsu.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orinuno.configuration.JutsuSyncProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.drift.JutsuDriftException;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.info.JutsuEpisodeListing;
import com.orinuno.jutsu.info.JutsuSeason;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.model.JutsuTitleStatus;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import jakarta.annotation.Nullable;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background sync worker for the jut.su L1 cache (ADR 0016 P1a). Drives two independent @Scheduled
 * loops:
 *
 * <ul>
 *   <li>{@link #fullCrawl()} — every {@code orinuno.jutsu.sync.full-crawl-interval-hours} (default
 *       48h, min 24h). Walks {@link JutsuClient#browseCatalog(int)} forward until an empty /
 *       no-more page, upserts each {@link JutsuCatalogEntry} into {@code jutsu_title}, then
 *       resolves the full info page for fresh entries to populate {@code jutsu_episode} rows.
 *   <li>{@link #noticeIncremental()} — every {@code orinuno.jutsu.sync.notice-interval-minutes}
 *       (default 5 min). Reads {@code jutsu_sync_state.last_notice_cursor}, fetches the latest feed
 *       via {@link JutsuClient#getLatestNoticeFeed()}, processes only the entries that are newer
 *       than the saved cursor, then advances the cursor to {@code feed.requestedCursor()}.
 * </ul>
 *
 * <p>Hard rules from ADR 0016 §"Catalog sync workers as a first-class subsystem":
 *
 * <ul>
 *   <li>The worker NEVER calls a controller / REST layer.
 *   <li>The worker NEVER blocks on user requests; it owns its own {@code TaskScheduler}.
 *   <li>{@link JutsuDriftException} or repeated parse failures null the cursor and force the next
 *       full crawl — but the bean does NOT crash.
 *   <li>The notice walk is single-leader: {@link JutsuNoticeLockService} manages the {@code
 *       notice_walk_in_progress} flag in its own transaction (avoids the self-invocation pitfall
 *       documented on {@code ParseRequestQueueService}).
 * </ul>
 */
@Slf4j
public class JutsuCatalogSyncService {

    /** Hard cap on pages walked per full crawl. Defensive guard against an infinite-page parser. */
    static final int MAX_FULL_CRAWL_PAGES = 200;

    private final JutsuClient jutsuClient;
    private final JutsuTitleRepository titleRepository;
    private final JutsuEpisodeRepository episodeRepository;
    private final JutsuNoticeLockService lockService;
    private final JutsuStalenessTracker stalenessTracker;
    private final JutsuSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public JutsuCatalogSyncService(
            JutsuClient jutsuClient,
            JutsuTitleRepository titleRepository,
            JutsuEpisodeRepository episodeRepository,
            JutsuNoticeLockService lockService,
            JutsuStalenessTracker stalenessTracker,
            JutsuSyncProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        this.jutsuClient = jutsuClient;
        this.titleRepository = titleRepository;
        this.episodeRepository = episodeRepository;
        this.lockService = lockService;
        this.stalenessTracker = stalenessTracker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // -------------------------------------------------------------------------
    // Full crawl
    // -------------------------------------------------------------------------

    public void fullCrawl() {
        log.info(
                "🌀 jut.su full crawl starting (interval={}h)",
                properties.effectiveFullCrawlIntervalHours());
        int upserted = 0;
        try {
            for (int page = 1; page <= MAX_FULL_CRAWL_PAGES; page++) {
                JutsuCatalogPage catalogPage;
                try {
                    catalogPage = jutsuClient.browseCatalog(page).block();
                } catch (JutsuDriftException drift) {
                    log.warn(
                            "⚠️ jut.su full crawl aborted by drift on page={} signal={}",
                            page,
                            drift.event() == null ? "n/a" : drift.event().signal(),
                            drift);
                    break;
                } catch (RuntimeException ex) {
                    log.warn("⚠️ jut.su full crawl: page={} failed; aborting this tick", page, ex);
                    break;
                }
                if (catalogPage == null || catalogPage.isEmpty()) {
                    log.debug("✅ jut.su full crawl: page={} empty, terminating", page);
                    break;
                }
                upserted += upsertCatalogPage(catalogPage);
                if (!catalogPage.hasMore()) {
                    log.debug("✅ jut.su full crawl: page={} hasMore=false", page);
                    break;
                }
            }
            lockService.markFullCrawl();
            stalenessTracker.invalidate();
            log.info("✅ jut.su full crawl finished, titles touched={}", upserted);
        } catch (RuntimeException ex) {
            log.error("❌ jut.su full crawl failed", ex);
        }
    }

    @Transactional
    int upsertCatalogPage(JutsuCatalogPage page) {
        int upserts = 0;
        LocalDateTime ts = now();
        for (JutsuCatalogEntry entry : page.entries()) {
            JutsuTitle title = catalogEntryToTitle(entry, ts);
            titleRepository.upsert(title);
            upserts++;
            // CatalogIngestionService binding is part of P1b — see ADR 0016 §"New bounded context".
            // TODO ADR-0016 P1b: catalogIngestionService.attachSource(JUTSU, slug, externalIds, …)
        }
        return upserts;
    }

    /**
     * Resolve a single anime info page and upsert its episodes. Used by the live-fallback path so a
     * cache miss doesn't have to wait for the next scheduled tick.
     */
    @Transactional
    public Optional<JutsuTitle> upsertFromAnimeInfo(JutsuAnimeInfo info) {
        if (info == null) return Optional.empty();
        LocalDateTime ts = now();
        JutsuTitle title = animeInfoToTitle(info, ts);
        titleRepository.upsert(title);

        List<JutsuEpisode> rows = animeInfoToEpisodeRows(info, ts);
        if (!rows.isEmpty()) {
            episodeRepository.upsertBatch(rows);
        }
        return Optional.of(title);
    }

    /** Force a single episode row upsert (cache-miss path on /episode?url=...). */
    @Transactional
    public void upsertEpisode(JutsuEpisode episode) {
        if (episode == null) return;
        episodeRepository.upsertBatch(List.of(episode));
    }

    // -------------------------------------------------------------------------
    // Notice incremental
    // -------------------------------------------------------------------------

    /**
     * Read {@code getLatestNoticeFeed()}, dedup against the saved cursor, advance the cursor.
     *
     * <p>jut.su's notice IDs are contiguous and monotonically increase. {@code
     * feed.requestedCursor} is the newest notice ID in the feed; entries are newest-first. So if
     * the saved cursor is {@code C} and the latest feed advertises {@code N} ({@code N >= C}), the
     * new entries are {@code feed.entries().subList(0, min(N - C, entries.size()))}.
     *
     * <p>If {@code N - C} exceeds {@code feed.entries().size()} we logged a gap — the full crawl
     * will eventually reconcile it. We still advance the cursor to {@code N} so we don't keep
     * replaying the same window.
     */
    public void noticeIncremental() {
        if (!lockService.tryAcquire()) {
            log.debug("⏭️ jut.su notice walk already in progress, skipping");
            return;
        }
        try {
            Optional<Integer> savedCursorOpt = lockService.currentCursor();
            JutsuNoticeFeed feed = safeBlock(jutsuClient.getLatestNoticeFeed());
            if (feed == null) {
                log.debug("⏭️ jut.su getLatestNoticeFeed returned null; cursor unchanged");
                return;
            }
            int newest = feed.requestedCursor();
            if (savedCursorOpt.isEmpty()) {
                lockService.saveCursor(newest);
                log.info("✅ jut.su notice incremental: bootstrapped cursor={}", newest);
                return;
            }
            int saved = savedCursorOpt.get();
            if (newest <= saved) {
                log.debug(
                        "⏭️ jut.su notice incremental: newest={} <= saved={}, nothing to do",
                        newest,
                        saved);
                return;
            }
            int delta = newest - saved;
            int toProcess = Math.min(delta, feed.entries().size());
            if (delta > feed.entries().size()) {
                log.warn(
                        "⚠️ jut.su notice gap: delta={} > pageSize={}; full crawl will reconcile",
                        delta,
                        feed.entries().size());
            }
            int processed = applyNoticeEntries(feed.entries().subList(0, toProcess));
            lockService.saveCursor(newest);
            log.info(
                    "✅ jut.su notice incremental: processed={} cursor {} -> {}",
                    processed,
                    saved,
                    newest);
        } catch (JutsuDriftException drift) {
            log.warn(
                    "⚠️ jut.su notice walk hit drift; nulling cursor to force a fresh bootstrap",
                    drift);
            try {
                lockService.saveCursor(null);
            } catch (RuntimeException ex) {
                log.warn("⚠️ Failed to null notice cursor after drift", ex);
            }
        } catch (RuntimeException ex) {
            log.warn("⚠️ jut.su notice walk failed; cursor unchanged", ex);
        } finally {
            lockService.release();
        }
    }

    @Transactional
    int applyNoticeEntries(List<JutsuNoticeEntry> entries) {
        if (entries == null || entries.isEmpty()) return 0;
        LocalDateTime ts = now();
        Map<String, JutsuTitle> bySlug = new LinkedHashMap<>();
        List<JutsuEpisode> episodes = new ArrayList<>();
        for (JutsuNoticeEntry entry : entries) {
            bySlug.computeIfAbsent(
                    entry.slug(),
                    s ->
                            JutsuTitle.builder()
                                    .slug(s)
                                    .titleRu(entry.title())
                                    .lastSyncedAt(ts)
                                    .build());
            episodes.add(
                    JutsuEpisode.builder()
                            .titleSlug(entry.slug())
                            .season(entry.season())
                            .episode(entry.episode())
                            .embedUrl(entry.episodeUrl())
                            .lastSyncedAt(ts)
                            .build());
        }
        for (JutsuTitle t : bySlug.values()) {
            titleRepository.upsert(t);
        }
        if (!episodes.isEmpty()) episodeRepository.upsertBatch(episodes);
        return episodes.size();
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    JutsuTitle catalogEntryToTitle(JutsuCatalogEntry entry, LocalDateTime ts) {
        Integer year = entry.year().map(JutsuCatalogSyncService::yearSlugToInt).orElse(null);
        JutsuTitleStatus status =
                entry.year().map(JutsuCatalogSyncService::yearSlugToStatus).orElse(null);
        return JutsuTitle.builder()
                .slug(entry.slug())
                .titleRu(entry.title())
                .titleEn(entry.originalTitle())
                .status(status)
                .year(year)
                .episodesTotal(entry.episodeCount())
                .movieCount(entry.movieCount())
                .genres(joinGenres(entry.genres()))
                .types(joinTypes(entry.types()))
                .posterUrl(entry.thumbnailUrl())
                .lastSyncedAt(ts)
                .build();
    }

    JutsuTitle animeInfoToTitle(JutsuAnimeInfo info, LocalDateTime ts) {
        Integer year = info.year().map(JutsuCatalogSyncService::yearSlugToInt).orElse(null);
        JutsuTitleStatus status =
                info.year().map(JutsuCatalogSyncService::yearSlugToStatus).orElse(null);
        return JutsuTitle.builder()
                .slug(info.slug())
                .titleRu(info.title())
                .titleEn(info.originalTitle())
                .status(status)
                .year(year)
                .episodesTotal(info.totalEpisodeCount())
                .genres(joinGenres(info.genres()))
                .types(joinTypes(info.types()))
                .description(info.synopsis())
                .posterUrl(info.thumbnailUrl())
                .lastSyncedAt(ts)
                .build();
    }

    @Nullable
    static String joinGenres(java.util.Set<com.orinuno.jutsu.filter.JutsuGenre> genres) {
        if (genres == null || genres.isEmpty()) return null;
        return genres.stream()
                .map(com.orinuno.jutsu.filter.JutsuGenre::slug)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    @Nullable
    static String joinTypes(java.util.Set<com.orinuno.jutsu.filter.JutsuType> types) {
        if (types == null || types.isEmpty()) return null;
        return types.stream()
                .map(com.orinuno.jutsu.filter.JutsuType::slug)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    List<JutsuEpisode> animeInfoToEpisodeRows(JutsuAnimeInfo info, LocalDateTime ts) {
        List<JutsuEpisode> rows = new ArrayList<>();
        for (JutsuSeason season : info.seasons()) {
            for (JutsuEpisodeListing ep : season.episodes()) {
                rows.add(
                        JutsuEpisode.builder()
                                .titleSlug(ep.slug())
                                .season(ep.season())
                                .episode(ep.episode())
                                .embedUrl(ep.absoluteUrl())
                                .lastSyncedAt(ts)
                                .build());
            }
        }
        return rows;
    }

    @Nullable
    static Integer yearSlugToInt(com.orinuno.jutsu.filter.JutsuYear y) {
        if (y == null) return null;
        return switch (y) {
            case Y_2026 -> 2026;
            case Y_2025 -> 2025;
            case Y_2024 -> 2024;
                // Range / ongoing buckets don't have a single canonical year — leave null.
            case ONGOING, Y_2015_2023, Y_2008_2014, Y_2000_2007, BEFORE_2000 -> null;
        };
    }

    @Nullable
    static JutsuTitleStatus yearSlugToStatus(com.orinuno.jutsu.filter.JutsuYear y) {
        if (y == null) return null;
        return y == com.orinuno.jutsu.filter.JutsuYear.ONGOING
                ? JutsuTitleStatus.ONGOING
                : JutsuTitleStatus.RELEASED;
    }

    String serializeQualities(Map<String, String> qualities) {
        if (qualities == null || qualities.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(qualities);
        } catch (JsonProcessingException ex) {
            log.warn("⚠️ Failed to serialize jut.su qualities map; storing null", ex);
            return null;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    @Nullable
    private <T> T safeBlock(reactor.core.publisher.Mono<T> mono) {
        try {
            return mono.block();
        } catch (JutsuDriftException drift) {
            throw drift;
        } catch (RuntimeException ex) {
            log.warn("⚠️ jut.su notice fetch failed: {}", ex.toString());
            return null;
        }
    }
}
