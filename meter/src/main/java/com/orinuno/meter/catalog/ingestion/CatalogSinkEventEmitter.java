package com.orinuno.meter.catalog.ingestion;

import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEpisode;
import com.orinuno.contract.source.SourceEpisodeVariant;
import com.orinuno.contract.source.SourceEventEmitter;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.contract.source.SourceSeason;
import com.orinuno.meter.catalog.api.CatalogIdentityRequest;
import com.orinuno.meter.catalog.api.CatalogPublicApi;
import com.orinuno.meter.catalog.model.CatalogContent;
import com.orinuno.meter.catalog.model.CatalogContentKind;
import com.orinuno.meter.catalog.model.CatalogSourceType;
import com.orinuno.meter.catalog.model.EpisodeSource;
import com.orinuno.meter.catalog.repository.EpisodeSourceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default in-process {@link SourceEventEmitter} (ADR 0017). Translates a {@link SourceCatalogEvent}
 * into the catalog context's internal {@link CatalogIdentityRequest} and calls {@link
 * CatalogPublicApi#findOrCreateContent(CatalogIdentityRequest)} synchronously inside the caller's
 * transaction; once the canonical {@link CatalogContent} is resolved, every embedded {@link
 * SourceEpisodeVariant} is upserted into {@link EpisodeSourceRepository} (ADR 0021 Block B2).
 *
 * <p>Failure isolation: any {@link RuntimeException} from the resolver or the L2 upserts is caught
 * and logged at WARN. Source-context L1 writes are the system of record — they must never break
 * because L3 produced a transient deadlock or the L2 upsert hit a not-yet-fixed bug. A subsequent
 * emit on the same identifier re-attempts the binding (idempotent by design — see {@link
 * CatalogPublicApi} + {@link EpisodeSourceRepository#upsert}).
 *
 * <p>{@code episode_video} is intentionally NOT written by this emitter — events today carry the
 * pre-decode URL (Kodik iframe / jut.su episode page) as {@code SourceEpisodeVariant.mediaUrl}, not
 * a decoded CDN URL. The post-decode video URL flows into L2 via a separate path (today
 * orinuno-app's {@code KodikEpisodeDualWriteService}; future: a post-decode event variant).
 *
 * <p>{@link SourceCatalogEvent.SourceRemoved} stays a no-op — soft-removal of L3/L2 rows is
 * deferred. {@link SourceCatalogEvent.EpisodesUpdated} bypasses the chrome-update path (no {@code
 * info} on that variant) and resolves the canonical row by the {@code (sourceType, sourceId)}
 * anchor.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogSinkEventEmitter implements SourceEventEmitter {

    private final CatalogPublicApi catalog;
    private final EpisodeSourceRepository episodeSources;
    private final Clock clock;

    @Override
    public void emit(SourceCatalogEvent event) {
        if (event == null) return;
        try {
            switch (event) {
                case SourceCatalogEvent.TitleObserved e -> resolveContent(e.identifier(), e.info());
                case SourceCatalogEvent.MovieDiscovered e -> {
                    CatalogContent content = resolveContent(e.identifier(), e.info());
                    if (content != null) {
                        ingestMovie(content.getId(), e.variant());
                    }
                }
                case SourceCatalogEvent.SeriesDiscovered e -> {
                    CatalogContent content = resolveContent(e.identifier(), e.info());
                    if (content != null) {
                        ingestSeasons(content.getId(), e.seasons());
                    }
                }
                case SourceCatalogEvent.EpisodesUpdated e -> {
                    CatalogContent content = resolveByAnchor(e.identifier());
                    if (content != null) {
                        ingestSeasons(content.getId(), e.seasons());
                    }
                }
                case SourceCatalogEvent.SourceRemoved e ->
                        log.debug(
                                "catalog-sink: ignoring SourceRemoved for {} — soft-removal not"
                                        + " implemented in P1b (deferred)",
                                e.identifier());
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "catalog-sink: emit({}) for identifier={} failed ({}: {}); L1 row is"
                            + " untouched, will retry on next emit",
                    event.getClass().getSimpleName(),
                    event.identifier(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    private CatalogContent resolveContent(SourceIdentifier identifier, SourceContentInfo info) {
        CatalogSourceType sourceType = mapSourceType(identifier.sourceType());
        if (sourceType == null) {
            log.debug(
                    "catalog-sink: skipping event for unrecognised sourceType='{}' (sourceId={})",
                    identifier.sourceType(),
                    identifier.sourceId());
            return null;
        }
        CatalogIdentityRequest request = toRequest(sourceType, identifier.sourceId(), info);
        return catalog.findOrCreateContent(request);
    }

    /**
     * Resolve the canonical row from the {@code (sourceType, sourceId)} anchor without overwriting
     * any chrome — used by {@link SourceCatalogEvent.EpisodesUpdated} which only carries episode
     * deltas. If no row exists yet, findOrCreateContent inserts a chromeless one; subsequent
     * episode upserts then have a valid {@code content_id} target.
     */
    private CatalogContent resolveByAnchor(SourceIdentifier identifier) {
        CatalogSourceType sourceType = mapSourceType(identifier.sourceType());
        if (sourceType == null) {
            log.debug(
                    "catalog-sink: skipping episodes-updated for unrecognised sourceType='{}'"
                            + " (sourceId={})",
                    identifier.sourceType(),
                    identifier.sourceId());
            return null;
        }
        CatalogIdentityRequest request =
                CatalogIdentityRequest.builder(sourceType, identifier.sourceId()).build();
        return catalog.findOrCreateContent(request);
    }

    /**
     * Films are stored as season=0, episode=1 (matches the {@link SourceSeason} javadoc
     * convention). One variant per movie event.
     */
    private void ingestMovie(long contentId, SourceEpisodeVariant variant) {
        upsertSource(contentId, 0, 1, variant);
    }

    private void ingestSeasons(long contentId, List<SourceSeason> seasons) {
        for (SourceSeason season : seasons) {
            for (SourceEpisode episode : season.episodes()) {
                for (SourceEpisodeVariant variant : episode.variants()) {
                    upsertSource(contentId, season.order(), episode.order(), variant);
                }
            }
        }
    }

    private void upsertSource(
            long contentId, int season, int episode, SourceEpisodeVariant variant) {
        LocalDateTime now = LocalDateTime.now(clock);
        String provider = variant.identifier().sourceType().toUpperCase(Locale.ROOT);
        EpisodeSource source =
                EpisodeSource.builder()
                        .contentId(contentId)
                        .season(season)
                        .episode(episode)
                        // Kodik carries translator semantics in the variant PK; jut.su has no
                        // translator concept, so the sourceId is just an episode-shaped key
                        // ("slug/s1/e1"). Both are written into translator_id verbatim — the
                        // unique key tolerates either shape since rows are still distinct.
                        .translatorId(variant.identifier().sourceId())
                        .translatorName(variant.title())
                        .provider(provider)
                        .sourceUrl(variant.mediaUrl())
                        .sourceType(provider)
                        .discoveredAt(now)
                        .lastSeenAt(now)
                        .build();
        try {
            episodeSources.upsert(source);
        } catch (RuntimeException ex) {
            log.warn(
                    "catalog-sink: episode_source upsert failed for content_id={} s={} e={}"
                            + " provider={} translator_id={} ({}: {})",
                    contentId,
                    season,
                    episode,
                    provider,
                    variant.identifier().sourceId(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage());
        }
    }

    /**
     * Translate the open-string {@code sourceType} carried by {@link SourceIdentifier} into the
     * catalog context's closed {@link CatalogSourceType} enum. Unknown sources fall through to
     * {@code null} — the event is then logged + ignored. Keep the matching deliberate (uppercase
     * comparison against the wire form) rather than reflective so accidental new sources don't
     * silently start writing into the canonical catalog without a code change.
     */
    private static CatalogSourceType mapSourceType(String wire) {
        if (wire == null) return null;
        return switch (wire.trim().toLowerCase(Locale.ROOT)) {
            case "kodik" -> CatalogSourceType.KODIK;
            case "jutsu" -> CatalogSourceType.JUTSU;
            default -> null;
        };
    }

    /**
     * Build the {@link CatalogIdentityRequest} the resolver expects. Source-context-typed external
     * ids ({@link CatalogSourceType#KODIK}, {@link CatalogSourceType#JUTSU}) are not passed through
     * — they live on the {@code (sourceType, sourceId)} anchor itself. Only the external-database
     * ids (Shikimori / IMDB / Kinopoisk / MAL / MDL / TMDB) are forwarded; the worldart-* ids on
     * {@link ExternalIds} are accepted by the contract but the catalog's resolver does not yet have
     * columns for them — drop silently rather than fail the emit.
     */
    static CatalogIdentityRequest toRequest(
            CatalogSourceType sourceType, String sourceId, SourceContentInfo info) {
        ExternalIds externalIds = info.externalIds();
        return CatalogIdentityRequest.builder(sourceType, sourceId)
                .titleRu(info.titleRu())
                .titleEn(info.titleEn())
                .kind(mapKind(info.kindHint()))
                .year(info.year())
                .shikimoriId(externalIds.shikimoriId())
                .malId(externalIds.malId())
                .imdbId(externalIds.imdbId())
                .kinopoiskId(externalIds.kinopoiskId())
                .mdlId(externalIds.mdlId())
                .tmdbId(externalIds.tmdbId())
                .build();
    }

    /**
     * 1:1 mapping from the contract's {@link ContentKindHint} into the catalog's internal {@link
     * CatalogContentKind}. {@link ContentKindHint#UNKNOWN} maps to {@link
     * CatalogContentKind#UNKNOWN} which the resolver treats as "leave the canonical kind alone"
     * (COALESCE-protected at the SQL layer — a richer source observation already won).
     */
    static CatalogContentKind mapKind(ContentKindHint hint) {
        if (hint == null) return CatalogContentKind.UNKNOWN;
        return switch (hint) {
            case MOVIE -> CatalogContentKind.MOVIE;
            case SERIES -> CatalogContentKind.SERIES;
            case ANIME -> CatalogContentKind.ANIME;
            case UNKNOWN -> CatalogContentKind.UNKNOWN;
        };
    }
}
