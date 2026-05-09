package com.orinuno.catalog.ingestion;

import com.orinuno.catalog.api.CatalogIdentityRequest;
import com.orinuno.catalog.api.CatalogPublicApi;
import com.orinuno.catalog.model.CatalogContentKind;
import com.orinuno.catalog.model.CatalogSourceType;
import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEventEmitter;
import com.orinuno.contract.source.SourceIdentifier;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default in-process {@link SourceEventEmitter} (ADR 0017). Translates a {@link SourceCatalogEvent}
 * into the catalog context's internal {@link CatalogIdentityRequest} and calls {@link
 * CatalogPublicApi#findOrCreateContent(CatalogIdentityRequest)} synchronously inside the caller's
 * transaction.
 *
 * <p>Failure isolation: any {@link RuntimeException} from the resolver is caught and logged at
 * WARN. Source-context L1 writes ({@code ContentService.findOrCreateContent}, {@code
 * JutsuCatalogSyncService.runFullCrawl}) are the system of record for "we observed this row" — they
 * must never break because L3 produced a transient deadlock or the resolver hit a not-yet-fixed
 * bug. A subsequent emit on the same identifier re-attempts the binding (idempotent by design — see
 * {@link CatalogPublicApi}).
 *
 * <p>Today only {@link SourceCatalogEvent.TitleObserved} carries enough information for the L1 → L3
 * hand-off; the four "richer" variants (movie / series / episodes / removed) are accepted but pass
 * only their chrome through to {@code findOrCreateContent}, leaving episode-level work for a later
 * phase. Logged at DEBUG so tests can observe the handling without polluting INFO.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogSinkEventEmitter implements SourceEventEmitter {

    private final CatalogPublicApi catalog;

    @Override
    public void emit(SourceCatalogEvent event) {
        if (event == null) return;
        try {
            switch (event) {
                case SourceCatalogEvent.TitleObserved e ->
                        handleTitleEvent(e.identifier(), e.info());
                case SourceCatalogEvent.MovieDiscovered e ->
                        handleTitleEvent(e.identifier(), e.info());
                case SourceCatalogEvent.SeriesDiscovered e ->
                        handleTitleEvent(e.identifier(), e.info());
                case SourceCatalogEvent.EpisodesUpdated e ->
                        log.debug(
                                "catalog-sink: ignoring EpisodesUpdated for {} — episode-level"
                                        + " ingestion not implemented in P1b (deferred to P2)",
                                e.identifier());
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

    private void handleTitleEvent(SourceIdentifier identifier, SourceContentInfo info) {
        CatalogSourceType sourceType = mapSourceType(identifier.sourceType());
        if (sourceType == null) {
            log.debug(
                    "catalog-sink: skipping event for unrecognised sourceType='{}' (sourceId={})",
                    identifier.sourceType(),
                    identifier.sourceId());
            return;
        }
        CatalogIdentityRequest request = toRequest(sourceType, identifier.sourceId(), info);
        catalog.findOrCreateContent(request);
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
