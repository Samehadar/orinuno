package com.orinuno.jutsu.sync;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.catalog.JutsuCatalogEntry;
import com.orinuno.jutsu.catalog.JutsuCatalogPage;
import com.orinuno.jutsu.catalog.JutsuCatalogRequest;
import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.model.JutsuSyncState;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.repository.JutsuSyncStateRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private final JutsuSyncStateRepository syncStateRepository;
    private final OrinunoProperties properties;

    public JutsuCatalogSyncService(
            JutsuClient client,
            JutsuTitleRepository titleRepository,
            JutsuSyncStateRepository syncStateRepository,
            OrinunoProperties properties) {
        this.client = client;
        this.titleRepository = titleRepository;
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
            for (JutsuCatalogEntry entry : response.entries()) {
                titleRepository.upsert(toTitle(entry, now));
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

    static JutsuTitle toTitle(JutsuCatalogEntry entry, LocalDateTime now) {
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
}
