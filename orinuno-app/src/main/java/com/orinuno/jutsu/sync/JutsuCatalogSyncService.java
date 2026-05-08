package com.orinuno.jutsu.sync;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.info.JutsuAnimeInfo;
import com.orinuno.jutsu.info.JutsuEpisodeListing;
import com.orinuno.jutsu.info.JutsuSeason;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuSyncState;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.notice.JutsuNoticeEntry;
import com.orinuno.jutsu.notice.JutsuNoticeFeed;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuSyncStateRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Catalog sync worker for jut.su's L1 cache (ARCH-0016 P1a Step 2).
 *
 * <p>The full-crawl loop walks {@code POST /anime/} page by page in declaration order, mapping
 * every {@link JutsuCatalogEntry} into a {@code jutsu_title} row via {@link
 * JutsuTitleRepository#upsert(JutsuTitle)}. The worker is split into <em>ticks</em> sized by {@code
 * orinuno.providers.jutsu.sync.full-crawl.max-pages-per-tick}; each tick advances the persistent
 * cursor in {@code jutsu_sync_state} so a crash mid-crawl resumes from the last completed page on
 * the next tick rather than restarting at page 1.
 *
 * <p>Two crawl phases are tracked on the singleton state row:
 *
 * <ul>
 *   <li>{@code fullCrawlStartedAt} > {@code fullCrawlCompletedAt} (or completed null) — a crawl is
 *       in progress; the next tick fetches {@code fullCrawlLastPage + 1}.
 *   <li>{@code fullCrawlCompletedAt >= fullCrawlStartedAt} (or both null) — last crawl finished;
 *       the next tick starts a fresh crawl at page 1 and resets {@code fullCrawlStartedAt = now}.
 * </ul>
 *
 * <p>The mapper writes catalog-only fields ({@code catalogEpisodeCount}, {@code catalogMovieCount},
 * {@code yearBucket}, {@code genresCsv}, {@code typesCsv}, {@code thumbnailUrl}, {@code
 * originalTitle}, {@code siteId}) plus the timestamps. Info-page-only fields ({@code synopsis},
 * {@code infoTotalSeasons}, {@code infoTotalEpisodes}, {@code infoFetchedAt}) stay {@code null} on
 * the way out — the {@code COALESCE(VALUES(c), c)} clause in {@code JutsuTitleMapper.upsert}
 * preserves whatever the info-page worker (Step 2.B / Step 3) wrote on previous ticks.
 *
 * <p>Networking is reactive but the worker blocks on each page response for two reasons: it runs on
 * the {@code decoderMaintenanceTaskScheduler} pool (already configured for blocking work) and it
 * needs strict serialisation between page fetches and DB writes for the resume-cursor invariant to
 * hold. Failure on any page records {@code lastError}/{@code lastErrorAt} in the state and stops
 * the tick early — the cursor is still advanced so the next tick picks up from the failure point on
 * the assumption that the upstream blip was transient.
 */
@Slf4j
@Component
public class JutsuCatalogSyncService {

    /**
     * Per-page block timeout. Generous on purpose: jut.su's catalog AJAX is usually sub-second but
     * we've seen 5-10s p99 spikes during peak traffic, and the rate limiter can hold a request for
     * up to 1s at the default 1 RPS budget.
     */
    static final Duration PAGE_TIMEOUT = Duration.ofSeconds(20);

    private final JutsuClient client;
    private final JutsuTitleRepository titleRepository;
    private final JutsuEpisodeRepository episodeRepository;
    private final JutsuSyncStateRepository syncStateRepository;
    private final OrinunoProperties properties;

    public JutsuCatalogSyncService(
            JutsuClient client,
            JutsuTitleRepository titleRepository,
            JutsuEpisodeRepository episodeRepository,
            JutsuSyncStateRepository syncStateRepository,
            OrinunoProperties properties) {
        this.client = client;
        this.titleRepository = titleRepository;
        this.episodeRepository = episodeRepository;
        this.syncStateRepository = syncStateRepository;
        this.properties = properties;
    }

    /**
     * Run one full-crawl tick. Returns the outcome regardless of whether the tick reached the
     * catalog terminus or stopped after the page cap; callers (scheduler, admin endpoint) use the
     * result to decide whether to log success or surface a warning.
     *
     * @param maxPages hard cap on pages fetched this tick. {@code <= 0} reverts to the configured
     *     {@code orinuno.providers.jutsu.sync.full-crawl.max-pages-per-tick} default; the caller
     *     should normally pass that exact value through.
     */
    public FullCrawlResult runFullCrawlOnce(int maxPages) {
        OrinunoProperties.JutsuProperties.SyncProperties cfg =
                properties.getProviders().getJutsu().getSync();
        if (!cfg.isEnabled() || !cfg.getFullCrawl().isEnabled()) {
            log.debug("jutsu-sync: full-crawl disabled, skipping tick");
            return FullCrawlResult.skipped();
        }
        int cap = maxPages > 0 ? maxPages : cfg.getFullCrawl().getMaxPagesPerTick();
        if (cap <= 0) {
            log.warn("jutsu-sync: full-crawl maxPagesPerTick={} ≤ 0, skipping", cap);
            return FullCrawlResult.skipped();
        }

        LocalDateTime now = LocalDateTime.now();
        JutsuSyncState state = ensureSingleton(now);
        boolean resumingPreviousCrawl = isCrawlInProgress(state);
        int startPage = resumingPreviousCrawl ? state.getFullCrawlLastPage() + 1 : 1;
        if (!resumingPreviousCrawl) {
            state.setFullCrawlStartedAt(now);
            state.setFullCrawlCompletedAt(null);
            state.setFullCrawlTotalPages(null);
        }

        int pagesFetched = 0;
        int titlesUpserted = 0;
        int lastPage = state.getFullCrawlLastPage() == null ? 0 : state.getFullCrawlLastPage();
        boolean completed = false;
        String error = null;

        for (int page = startPage; pagesFetched < cap; page++) {
            JutsuCatalogPage response;
            try {
                response =
                        client.browseCatalog(JutsuCatalogRequest.unfiltered(page))
                                .block(PAGE_TIMEOUT);
            } catch (RuntimeException ex) {
                error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                log.warn(
                        "jutsu-sync: full-crawl page={} failed ({}), stopping tick early",
                        page,
                        error);
                break;
            }
            if (response == null) {
                error = "browseCatalog returned null";
                log.warn("jutsu-sync: full-crawl page={} returned null", page);
                break;
            }

            pagesFetched++;
            lastPage = page;
            int slot = 0;
            for (JutsuCatalogEntry entry : response.entries()) {
                slot++;
                titleRepository.upsert(toTitle(entry, page, slot, now));
                titlesUpserted++;
            }
            if (!response.hasMore()) {
                completed = true;
                break;
            }
        }

        state.setFullCrawlLastPage(lastPage);
        if (completed) {
            state.setFullCrawlCompletedAt(LocalDateTime.now());
            state.setFullCrawlTotalPages(lastPage);
        }
        state.setTotalTitlesSynced(state.getTotalTitlesSynced() + titlesUpserted);
        if (error == null) {
            state.setLastError(null);
            state.setLastErrorAt(null);
        } else {
            state.setLastError(truncate(error, 1024));
            state.setLastErrorAt(LocalDateTime.now());
        }
        state.setUpdatedAt(LocalDateTime.now());
        syncStateRepository.update(state);

        log.info(
                "jutsu-sync: full-crawl tick done — pagesFetched={}, titlesUpserted={},"
                        + " resuming={}, completed={}, lastPage={}, error={}",
                pagesFetched,
                titlesUpserted,
                resumingPreviousCrawl,
                completed,
                lastPage,
                error == null ? "none" : error);
        return new FullCrawlResult(
                pagesFetched, titlesUpserted, completed, lastPage, resumingPreviousCrawl, error);
    }

    /**
     * Run one notice-walk tick (Step 2.B). Polls jut.su's "upcoming releases" notice feed to
     * discover newly-published slugs between full-crawl ticks; on a quiet site this costs exactly
     * one homepage GET + one feed POST per tick.
     *
     * <p>State machine on the persistent {@code noticeCursor}:
     *
     * <ul>
     *   <li>{@code noticeCursor == null} (very first tick after a fresh DB / a cursor reset): we
     *       only record the latest cursor and return without walking. Backfilling jut.su's entire
     *       notice history (potentially thousands of entries) on first boot is never what callers
     *       want — once the full crawl populates the cache, the notice walker only needs to track
     *       deltas going forward.
     *   <li>{@code noticeCursor &lt; latest} (the common case on a busy day): walk feeds newest-
     *       first up to {@code maxFeedsPerTick}, stopping early when the oldest entry of the
     *       current feed is at or below the saved cursor. Save {@code latest} as the new cursor.
     *   <li>{@code noticeCursor &gt;= latest}: idle — homepage hasn't published anything new since
     *       the last tick. Touch {@code noticeLastWalkedAt} for ops monitoring and exit.
     * </ul>
     *
     * <p>Discovered slugs are deduplicated; for each one we already have in the L1 cache (catalog
     * already saw it) we touch {@code last_seen_at} but leave the title untouched. For previously-
     * unseen slugs the behaviour depends on {@code fetch-info-on-discovery}: when enabled (and
     * within {@code maxInfoFetchesPerTick}) we fetch the full anime info page synchronously and
     * upsert both the title and its episode list; when disabled we record the slug with a
     * lightweight title-from-notice marker so the next full-crawl tick can hydrate the rest.
     *
     * <p>Notice entries' {@code title} field is the rendered "Anime Name: Episode N" form, NOT a
     * clean anime title. We deliberately never overwrite an existing {@code jutsu_title.title} with
     * a notice-derived value — only fresh slugs that have no row yet get the notice title as a
     * placeholder, and even then it's superseded by the next catalog tick.
     */
    public NoticeWalkResult runNoticeWalkOnce(int maxFeeds, int maxInfoFetches) {
        OrinunoProperties.JutsuProperties.SyncProperties cfg =
                properties.getProviders().getJutsu().getSync();
        OrinunoProperties.JutsuProperties.SyncProperties.NoticeWalkProperties nwCfg =
                cfg.getNoticeWalk();
        if (!cfg.isEnabled() || !nwCfg.isEnabled()) {
            log.debug("jutsu-sync: notice-walk disabled, skipping tick");
            return NoticeWalkResult.skipped();
        }
        int feedCap = maxFeeds > 0 ? maxFeeds : nwCfg.getMaxFeedsPerTick();
        int infoCap = maxInfoFetches >= 0 ? maxInfoFetches : nwCfg.getMaxInfoFetchesPerTick();
        if (feedCap <= 0) {
            log.warn("jutsu-sync: notice-walk maxFeedsPerTick={} ≤ 0, skipping", feedCap);
            return NoticeWalkResult.skipped();
        }

        LocalDateTime now = LocalDateTime.now();
        JutsuSyncState state = ensureSingleton(now);
        Integer savedCursor = state.getNoticeCursor();

        JutsuNoticeFeed firstFeed;
        try {
            firstFeed = client.getLatestNoticeFeed().block(PAGE_TIMEOUT);
        } catch (RuntimeException ex) {
            String error = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.warn("jutsu-sync: notice-walk failed to discover latest cursor ({})", error);
            recordNoticeError(state, error);
            return NoticeWalkResult.failed(error);
        }
        if (firstFeed == null) {
            String error = "getLatestNoticeFeed returned null";
            log.warn("jutsu-sync: {}", error);
            recordNoticeError(state, error);
            return NoticeWalkResult.failed(error);
        }
        int discoveredCursor = firstFeed.requestedCursor();

        if (savedCursor == null) {
            log.info(
                    "jutsu-sync: notice-walk first tick — recording latest cursor {} without"
                            + " backfilling history",
                    discoveredCursor);
            state.setNoticeCursor(discoveredCursor);
            state.setNoticeCursorUpdatedAt(LocalDateTime.now());
            state.setNoticeLastWalkedAt(LocalDateTime.now());
            state.setLastError(null);
            state.setLastErrorAt(null);
            state.setUpdatedAt(LocalDateTime.now());
            syncStateRepository.update(state);
            return NoticeWalkResult.firstTick(discoveredCursor);
        }
        if (discoveredCursor <= savedCursor) {
            state.setNoticeLastWalkedAt(LocalDateTime.now());
            state.setUpdatedAt(LocalDateTime.now());
            syncStateRepository.update(state);
            return NoticeWalkResult.idle(savedCursor);
        }

        LinkedHashSet<String> uniqueSlugs = new LinkedHashSet<>();
        int feedsWalked = 0;
        JutsuNoticeFeed feed = firstFeed;
        String walkError = null;
        try {
            while (feedsWalked < feedCap && feed != null && feed.hasEntries()) {
                feedsWalked++;
                int oldestIdInFeed = feed.requestedCursor() - feed.entries().size() + 1;
                for (JutsuNoticeEntry entry : feed.entries()) {
                    uniqueSlugs.add(entry.slug());
                }
                if (oldestIdInFeed <= savedCursor + 1) {
                    break;
                }
                Optional<Integer> next = feed.nextCursor();
                if (next.isEmpty()) break;
                feed = client.getNoticeFeed(next.get()).block(PAGE_TIMEOUT);
            }
        } catch (RuntimeException ex) {
            walkError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.warn(
                    "jutsu-sync: notice-walk feed iteration failed ({}), processing {} slugs"
                            + " collected so far",
                    walkError,
                    uniqueSlugs.size());
        }

        // Resolve which discovered slugs are already in L1 vs brand new. Bulk lookup avoids N+1.
        Set<String> existingSlugs = Set.of();
        if (!uniqueSlugs.isEmpty()) {
            existingSlugs =
                    titleRepository.findBySlugs(new ArrayList<>(uniqueSlugs)).stream()
                            .map(JutsuTitle::getSlug)
                            .collect(Collectors.toUnmodifiableSet());
        }

        int infoFetched = 0;
        int placeholderUpserts = 0;
        for (String slug : uniqueSlugs) {
            if (existingSlugs.contains(slug)) continue;
            if (nwCfg.isFetchInfoOnDiscovery() && infoFetched < infoCap) {
                JutsuAnimeInfo info;
                try {
                    info = client.getAnimeInfo(slug).block(PAGE_TIMEOUT);
                } catch (RuntimeException ex) {
                    log.warn(
                            "jutsu-sync: notice-walk getAnimeInfo({}) failed ({}), skipping slug",
                            slug,
                            ex.toString());
                    continue;
                }
                if (info == null) continue;
                titleRepository.upsert(infoToTitle(info, LocalDateTime.now()));
                List<JutsuEpisode> episodes = infoToEpisodes(info, LocalDateTime.now());
                if (!episodes.isEmpty()) {
                    episodeRepository.upsertAll(episodes);
                }
                infoFetched++;
            } else {
                JutsuNoticeEntry sample = findFirstEntryForSlug(uniqueSlugs, firstFeed, slug);
                titleRepository.upsert(noticeToPlaceholderTitle(sample, slug, LocalDateTime.now()));
                placeholderUpserts++;
            }
        }

        state.setNoticeCursor(discoveredCursor);
        state.setNoticeCursorUpdatedAt(LocalDateTime.now());
        state.setNoticeLastWalkedAt(LocalDateTime.now());
        if (walkError == null) {
            state.setLastError(null);
            state.setLastErrorAt(null);
        } else {
            state.setLastError(truncate(walkError, 1024));
            state.setLastErrorAt(LocalDateTime.now());
        }
        state.setUpdatedAt(LocalDateTime.now());
        syncStateRepository.update(state);

        log.info(
                "jutsu-sync: notice-walk tick — feedsWalked={}, uniqueSlugs={}, newInfoFetched={},"
                        + " newPlaceholders={}, savedCursor {} → {}, error={}",
                feedsWalked,
                uniqueSlugs.size(),
                infoFetched,
                placeholderUpserts,
                savedCursor,
                discoveredCursor,
                walkError == null ? "none" : walkError);
        return new NoticeWalkResult(
                feedsWalked,
                uniqueSlugs.size(),
                infoFetched,
                placeholderUpserts,
                savedCursor,
                discoveredCursor,
                walkError);
    }

    /**
     * Convenience overload that reads the configured caps from {@code OrinunoProperties}. Used by
     * the scheduler; callers that want to override the caps (manual triggers, tests) should use
     * {@link #runNoticeWalkOnce(int, int)} directly.
     */
    public NoticeWalkResult runNoticeWalkOnce() {
        OrinunoProperties.JutsuProperties.SyncProperties.NoticeWalkProperties nwCfg =
                properties.getProviders().getJutsu().getSync().getNoticeWalk();
        return runNoticeWalkOnce(nwCfg.getMaxFeedsPerTick(), nwCfg.getMaxInfoFetchesPerTick());
    }

    private void recordNoticeError(JutsuSyncState state, String error) {
        state.setNoticeLastWalkedAt(LocalDateTime.now());
        state.setLastError(truncate(error, 1024));
        state.setLastErrorAt(LocalDateTime.now());
        state.setUpdatedAt(LocalDateTime.now());
        syncStateRepository.update(state);
    }

    /**
     * Find a representative notice entry for the given slug. We only have the first feed in memory
     * (subsequent feeds may have been fetched in the loop and discarded), so this is a best-effort
     * lookup — when no entry matches we fall back to a slug-only placeholder.
     */
    private static JutsuNoticeEntry findFirstEntryForSlug(
            LinkedHashSet<String> walkedSlugs, JutsuNoticeFeed firstFeed, String slug) {
        if (!walkedSlugs.contains(slug)) return null;
        for (JutsuNoticeEntry entry : firstFeed.entries()) {
            if (slug.equals(entry.slug())) return entry;
        }
        return null;
    }

    private JutsuSyncState ensureSingleton(LocalDateTime now) {
        Optional<JutsuSyncState> existing = syncStateRepository.findSingleton();
        if (existing.isPresent()) {
            return existing.get();
        }
        JutsuSyncState fresh = JutsuSyncState.empty(now);
        syncStateRepository.initIfAbsent(fresh);
        // Re-read so we pick up any concurrently-inserted singleton row (INSERT IGNORE swallows
        // primary-key collisions silently). This keeps two workers booting at the same time from
        // each writing their own "fresh" snapshot on top of the other.
        return syncStateRepository.findSingleton().orElse(fresh);
    }

    static boolean isCrawlInProgress(JutsuSyncState state) {
        if (state.getFullCrawlStartedAt() == null) return false;
        if (state.getFullCrawlLastPage() == null || state.getFullCrawlLastPage() < 1) return false;
        if (state.getFullCrawlCompletedAt() == null) return true;
        return state.getFullCrawlCompletedAt().isBefore(state.getFullCrawlStartedAt());
    }

    /**
     * Catalog page size jut.su returns per AJAX call ({@code anime_page_next} kicks in after 30
     * cards). Pinned by upstream's pagination, so it stays a constant — if jut.su ever changes the
     * page size, the SDK's {@code JutsuCatalogPage.size()} returns the actual value and this
     * constant becomes stale; the read service notices because {@code catalog_position} stops
     * increasing monotonically.
     */
    static final int CATALOG_PAGE_SIZE = 30;

    /**
     * Build a {@link JutsuTitle} row from a catalog entry, attaching the 1-based crawl position
     * derived from the entry's {@code (page, slot)} coordinate. The position drives the read side's
     * default "by rating" sort because jut.su returns its default ranking as the page order itself.
     */
    static JutsuTitle toTitle(JutsuCatalogEntry entry, int page, int slot, LocalDateTime now) {
        int catalogPosition = (page - 1) * CATALOG_PAGE_SIZE + slot;
        return JutsuTitle.builder()
                .slug(entry.slug())
                .siteId(entry.siteId() > 0 ? entry.siteId() : null)
                .title(entry.title())
                .originalTitle(entry.originalTitle())
                .thumbnailUrl(entry.thumbnailUrl())
                .yearBucket(entry.year().map(JutsuYear::slug).orElse(null))
                .genresCsv(joinSlugs(entry.genres(), JutsuGenre::slug))
                .typesCsv(joinSlugs(entry.types(), JutsuType::slug))
                .catalogEpisodeCount(entry.episodeCount())
                .catalogMovieCount(entry.movieCount())
                .catalogPosition(catalogPosition)
                .catalogFetchedAt(now)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    private static <T> String joinSlugs(
            Set<T> values, java.util.function.Function<T, String> slug) {
        if (values == null || values.isEmpty()) return null;
        return values.stream().map(slug).sorted().collect(Collectors.joining(","));
    }

    /**
     * Build a {@link JutsuTitle} row from a fully-parsed anime info page. Catalog-only fields
     * ({@code catalogEpisodeCount}, {@code catalogMovieCount}, {@code catalogFetchedAt}) stay NULL
     * — those belong to the catalog tick and are protected by COALESCE on the way in. Info-only
     * fields ({@code synopsis}, {@code infoTotalSeasons}, {@code infoTotalEpisodes}, {@code
     * infoFetchedAt}) ARE populated; this is the path that hydrates them on first discovery.
     */
    static JutsuTitle infoToTitle(JutsuAnimeInfo info, LocalDateTime now) {
        return JutsuTitle.builder()
                .slug(info.slug())
                .title(info.title())
                .originalTitle(info.originalTitle())
                .synopsis(info.synopsis())
                .thumbnailUrl(info.thumbnailUrl())
                .yearBucket(info.year().map(JutsuYear::slug).orElse(null))
                .genresCsv(joinSlugs(info.genres(), JutsuGenre::slug))
                .typesCsv(joinSlugs(info.types(), JutsuType::slug))
                .infoTotalSeasons(info.seasons().size())
                .infoTotalEpisodes(info.totalEpisodeCount())
                .infoFetchedAt(now)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    /**
     * Flatten the season → episode tree from an info page into the flat shape backing {@code
     * jutsu_episode}. Order is stable (season ASC, episode ASC) so the bulk upsert produces a
     * deterministic SQL payload.
     */
    static List<JutsuEpisode> infoToEpisodes(JutsuAnimeInfo info, LocalDateTime now) {
        List<JutsuEpisode> out = new ArrayList<>();
        for (JutsuSeason season : info.seasons()) {
            for (JutsuEpisodeListing listing : season.episodes()) {
                out.add(
                        JutsuEpisode.builder()
                                .slug(info.slug())
                                .season(season.index())
                                .episode(listing.episode())
                                .label(listing.label())
                                .relativeUrl(listing.url())
                                .paywalled(null)
                                .discoveredAt(now)
                                .lastSeenAt(now)
                                .build());
            }
        }
        return out;
    }

    /**
     * Build a placeholder {@link JutsuTitle} from a notice-feed entry. Used only when the slug is
     * brand-new (not yet in L1) and {@code fetch-info-on-discovery} is disabled — we record the
     * slug + thumbnail with a synthesised title (the notice title is in {@code "Anime Name: Episode
     * N"} form, so we strip the trailing {@code ": ..."} fragment) so the next full-crawl tick can
     * complete the row. {@code title} is intentionally written because the COALESCE- protected
     * mapper will overwrite it on the next catalog upsert anyway — but a placeholder beats NULL for
     * the moments between the notice walk and the next catalog tick.
     */
    static JutsuTitle noticeToPlaceholderTitle(
            JutsuNoticeEntry entry, String slug, LocalDateTime now) {
        String synthesisedTitle = slug;
        String thumbnail = null;
        if (entry != null) {
            String raw = entry.title();
            int colonIdx = raw.indexOf(':');
            synthesisedTitle = colonIdx > 0 ? raw.substring(0, colonIdx).trim() : raw;
            if (synthesisedTitle.isBlank()) synthesisedTitle = slug;
            thumbnail = entry.thumbnailUrl();
        }
        return JutsuTitle.builder()
                .slug(slug)
                .title(synthesisedTitle)
                .thumbnailUrl(thumbnail)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Outcome of one {@link #runFullCrawlOnce(int)} tick.
     *
     * @param pagesFetched number of catalog pages successfully fetched and persisted this tick
     * @param titlesUpserted number of {@code jutsu_title} rows upserted this tick
     * @param completed {@code true} when the tick reached the catalog terminus ({@code hasMore =
     *     false}); {@code false} when it stopped due to the page cap or a fetch error
     * @param lastPage 1-based number of the last page persisted this tick (0 if none)
     * @param resumedPreviousCrawl {@code true} when this tick resumed an unfinished crawl from a
     *     previous tick, {@code false} when it started a fresh crawl
     * @param error short error description if the tick stopped due to a fetch failure; {@code null}
     *     on a clean tick
     */
    public record FullCrawlResult(
            int pagesFetched,
            int titlesUpserted,
            boolean completed,
            int lastPage,
            boolean resumedPreviousCrawl,
            String error) {

        /** Marker used when the tick is configured-disabled and shouldn't run at all. */
        public static FullCrawlResult skipped() {
            return new FullCrawlResult(0, 0, false, 0, false, null);
        }

        public boolean wasSuccessful() {
            return error == null;
        }

        public List<String> describe() {
            return List.of(
                    "pagesFetched=" + pagesFetched,
                    "titlesUpserted=" + titlesUpserted,
                    "completed=" + completed,
                    "lastPage=" + lastPage,
                    "resumedPreviousCrawl=" + resumedPreviousCrawl,
                    "error=" + (error == null ? "none" : error));
        }
    }

    /**
     * Outcome of one {@link #runNoticeWalkOnce(int, int)} tick.
     *
     * @param feedsWalked number of notice-feed pages successfully fetched this tick
     * @param uniqueSlugsDiscovered number of distinct slugs seen across the walked feeds
     * @param newInfoFetched number of newly-discovered slugs hydrated from {@code getAnimeInfo}
     * @param newPlaceholdersWritten number of newly-discovered slugs written as catalog
     *     placeholders (when {@code fetch-info-on-discovery=false} or the per-tick info budget was
     *     exhausted)
     * @param previousCursor cursor stored on the state row before this tick; {@code null} on the
     *     very first tick
     * @param newCursor cursor stored on the state row after this tick (always equal to the
     *     discovered latest cursor from the homepage)
     * @param error short error description if the walk stopped due to a fetch failure; {@code null}
     *     on a clean tick
     */
    public record NoticeWalkResult(
            int feedsWalked,
            int uniqueSlugsDiscovered,
            int newInfoFetched,
            int newPlaceholdersWritten,
            Integer previousCursor,
            Integer newCursor,
            String error) {

        /** Marker used when the tick is configured-disabled and shouldn't run at all. */
        public static NoticeWalkResult skipped() {
            return new NoticeWalkResult(0, 0, 0, 0, null, null, null);
        }

        /** First-ever tick: only records the cursor, doesn't backfill. */
        public static NoticeWalkResult firstTick(int cursor) {
            return new NoticeWalkResult(0, 0, 0, 0, null, cursor, null);
        }

        /** Idle tick: homepage hasn't published anything new since the last tick. */
        public static NoticeWalkResult idle(int cursor) {
            return new NoticeWalkResult(0, 0, 0, 0, cursor, cursor, null);
        }

        /** Failed tick: discovery or feed fetch raised an exception. */
        public static NoticeWalkResult failed(String error) {
            return new NoticeWalkResult(0, 0, 0, 0, null, null, error);
        }

        public boolean wasSuccessful() {
            return error == null;
        }

        public List<String> describe() {
            return List.of(
                    "feedsWalked=" + feedsWalked,
                    "uniqueSlugsDiscovered=" + uniqueSlugsDiscovered,
                    "newInfoFetched=" + newInfoFetched,
                    "newPlaceholdersWritten=" + newPlaceholdersWritten,
                    "previousCursor=" + previousCursor,
                    "newCursor=" + newCursor,
                    "error=" + (error == null ? "none" : error));
        }
    }
}
