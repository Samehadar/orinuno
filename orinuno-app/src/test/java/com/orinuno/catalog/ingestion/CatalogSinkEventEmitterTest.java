package com.orinuno.catalog.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.catalog.api.CatalogIdentityRequest;
import com.orinuno.catalog.api.CatalogPublicApi;
import com.orinuno.catalog.model.CatalogContent;
import com.orinuno.catalog.model.CatalogContentKind;
import com.orinuno.catalog.model.CatalogSourceType;
import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEpisodeVariant;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.contract.source.SourceSeason;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for the default in-process {@link CatalogSinkEventEmitter} (ADR 0017).
 * Verifies the translation from {@link SourceCatalogEvent} into the catalog context's internal
 * {@link CatalogIdentityRequest}, the source-type whitelist (only kodik / jutsu reach the resolver
 * today), the failure-isolation contract (resolver exceptions never propagate), and the
 * P1b-deferred handling of episode-level variants.
 */
@ExtendWith(MockitoExtension.class)
class CatalogSinkEventEmitterTest {

    @Mock private CatalogPublicApi catalog;

    private CatalogSinkEventEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new CatalogSinkEventEmitter(catalog);
    }

    @Test
    @DisplayName(
            "TitleObserved (kodik): translates into CatalogIdentityRequest with KODIK source"
                    + " type, kind=ANIME, all external-database ids forwarded")
    void kodikTitleObservedTranslatesToCatalogRequest() {
        when(catalog.findOrCreateContent(any()))
                .thenReturn(CatalogContent.builder().id(7L).build());

        SourceCatalogEvent event =
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("kodik", "movie-12345"),
                        SourceContentInfo.builder()
                                .titleRu("Наруто")
                                .titleEn("Naruto")
                                .year(2002)
                                .kindHint(ContentKindHint.ANIME)
                                .externalIds(
                                        ExternalIds.builder()
                                                .shikimoriId("1")
                                                .imdbId("tt0409591")
                                                .kinopoiskId("283290")
                                                .build())
                                .build(),
                        Provenance.of("https://kodik-api.com", Instant.now()));

        emitter.emit(event);

        CatalogIdentityRequest request = captureRequest();
        assertThat(request.sourceType()).isEqualTo(CatalogSourceType.KODIK);
        assertThat(request.sourceId()).isEqualTo("movie-12345");
        assertThat(request.titleRu()).isEqualTo("Наруто");
        assertThat(request.titleEn()).isEqualTo("Naruto");
        assertThat(request.kind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(request.year()).isEqualTo(2002);
        assertThat(request.externalId(CatalogSourceType.SHIKIMORI)).contains("1");
        assertThat(request.externalId(CatalogSourceType.IMDB)).contains("tt0409591");
        assertThat(request.externalId(CatalogSourceType.KINOPOISK)).contains("283290");
        assertThat(request.externalId(CatalogSourceType.MAL)).isEmpty();
    }

    @Test
    @DisplayName("TitleObserved (jutsu) with kindHint=ANIME → CatalogContentKind.ANIME")
    void jutsuTitleObservedTranslatesAnimeKind() {
        when(catalog.findOrCreateContent(any()))
                .thenReturn(CatalogContent.builder().id(8L).build());

        SourceCatalogEvent event =
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("jutsu", "naruto"),
                        SourceContentInfo.builder()
                                .titleRu("Наруто")
                                .kindHint(ContentKindHint.ANIME)
                                .externalIds(ExternalIds.empty())
                                .build(),
                        Provenance.of("https://jut.su/naruto/", Instant.now()));

        emitter.emit(event);

        CatalogIdentityRequest request = captureRequest();
        assertThat(request.sourceType()).isEqualTo(CatalogSourceType.JUTSU);
        assertThat(request.sourceId()).isEqualTo("naruto");
        assertThat(request.kind()).isEqualTo(CatalogContentKind.ANIME);
    }

    @Test
    @DisplayName(
            "MovieDiscovered passes the title chrome through findOrCreateContent (variant is"
                    + " carried but not yet applied — episode-level ingestion deferred to P2)")
    void movieDiscoveredForwardsChrome() {
        when(catalog.findOrCreateContent(any()))
                .thenReturn(CatalogContent.builder().id(9L).build());

        SourceCatalogEvent event =
                new SourceCatalogEvent.MovieDiscovered(
                        SourceIdentifier.of("kodik", "russian-movie-island-2006"),
                        SourceContentInfo.builder()
                                .titleRu("Остров")
                                .year(2006)
                                .kindHint(ContentKindHint.MOVIE)
                                .externalIds(ExternalIds.builder().kinopoiskId("253245").build())
                                .build(),
                        new SourceEpisodeVariant(
                                SourceIdentifier.of("kodik", "russian-movie-island-2006:rus"),
                                "https://cdn.kodik-api.com/island.mp4",
                                "Russian dub",
                                "1080p",
                                null,
                                null),
                        Provenance.of("https://kodik-api.com", Instant.now()));

        emitter.emit(event);

        CatalogIdentityRequest request = captureRequest();
        assertThat(request.kind()).isEqualTo(CatalogContentKind.MOVIE);
        assertThat(request.externalId(CatalogSourceType.KINOPOISK)).contains("253245");
    }

    @Test
    @DisplayName(
            "SeriesDiscovered passes the title chrome through findOrCreateContent (seasons"
                    + " carried but not yet applied — deferred to P2)")
    void seriesDiscoveredForwardsChrome() {
        when(catalog.findOrCreateContent(any()))
                .thenReturn(CatalogContent.builder().id(10L).build());

        SourceCatalogEvent event =
                new SourceCatalogEvent.SeriesDiscovered(
                        SourceIdentifier.of("jutsu", "naruto"),
                        SourceContentInfo.builder()
                                .titleRu("Наруто")
                                .kindHint(ContentKindHint.ANIME)
                                .externalIds(ExternalIds.empty())
                                .build(),
                        List.of(new SourceSeason(null, null, null, 1, List.of())),
                        Provenance.of("https://jut.su/naruto/", Instant.now()));

        emitter.emit(event);

        CatalogIdentityRequest request = captureRequest();
        assertThat(request.sourceType()).isEqualTo(CatalogSourceType.JUTSU);
        assertThat(request.kind()).isEqualTo(CatalogContentKind.ANIME);
    }

    @Test
    @DisplayName(
            "EpisodesUpdated carries no chrome → emitter logs and skips findOrCreateContent"
                    + " (deferred to P2)")
    void episodesUpdatedIsCurrentlyANoop() {
        SourceCatalogEvent event =
                new SourceCatalogEvent.EpisodesUpdated(
                        SourceIdentifier.of("jutsu", "naruto"),
                        List.of(new SourceSeason(null, null, null, 1, List.of())),
                        Provenance.of("https://jut.su/naruto/", Instant.now()));

        emitter.emit(event);

        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName(
            "SourceRemoved is currently a no-op (P1b deferred soft-removal) — emitter logs and"
                    + " does not touch CatalogPublicApi")
    void sourceRemovedIsCurrentlyANoop() {
        SourceCatalogEvent event =
                new SourceCatalogEvent.SourceRemoved(
                        SourceIdentifier.of("kodik", "movie-deprecated-99"),
                        Provenance.of("https://kodik-api.com", Instant.now()));

        emitter.emit(event);

        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName(
            "Unknown sourceType (e.g. an OSS consumer's future 'aniboom' / 'sibnet' before they"
                    + " gain L1 cache support) is silently dropped — never reaches the resolver")
    void unknownSourceTypeIsSilentlyDropped() {
        SourceCatalogEvent event =
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("aniboom", "some-id"),
                        SourceContentInfo.builder()
                                .titleRu("X")
                                .kindHint(ContentKindHint.ANIME)
                                .externalIds(ExternalIds.empty())
                                .build(),
                        Provenance.of("https://aniboom.example", Instant.now()));

        emitter.emit(event);

        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName(
            "resolver exception is caught and logged WARN — emit() returns normally; source"
                    + " contexts rely on this contract to keep L1 writes independent from L3")
    void resolverExceptionIsSwallowed() {
        when(catalog.findOrCreateContent(any()))
                .thenThrow(new RuntimeException("boom from resolver"));

        emitter.emit(
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("kodik", "movie-x"),
                        SourceContentInfo.builder()
                                .titleRu("X")
                                .kindHint(ContentKindHint.ANIME)
                                .externalIds(ExternalIds.empty())
                                .build(),
                        Provenance.of("https://kodik-api.com", Instant.now())));

        verify(catalog).findOrCreateContent(any());
    }

    @Test
    @DisplayName("null event is a no-op (defensive)")
    void nullEventIsNoop() {
        emitter.emit(null);
        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName(
            "kindHint mapping: every ContentKindHint maps 1:1 to the catalog's CatalogContentKind"
                    + " (UNKNOWN preserved so resolver leaves the canonical kind alone)")
    void kindHintMapping() {
        assertThat(CatalogSinkEventEmitter.mapKind(ContentKindHint.MOVIE))
                .isEqualTo(CatalogContentKind.MOVIE);
        assertThat(CatalogSinkEventEmitter.mapKind(ContentKindHint.SERIES))
                .isEqualTo(CatalogContentKind.SERIES);
        assertThat(CatalogSinkEventEmitter.mapKind(ContentKindHint.ANIME))
                .isEqualTo(CatalogContentKind.ANIME);
        assertThat(CatalogSinkEventEmitter.mapKind(ContentKindHint.UNKNOWN))
                .isEqualTo(CatalogContentKind.UNKNOWN);
        assertThat(CatalogSinkEventEmitter.mapKind(null)).isEqualTo(CatalogContentKind.UNKNOWN);
    }

    private CatalogIdentityRequest captureRequest() {
        ArgumentCaptor<CatalogIdentityRequest> captor =
                ArgumentCaptor.forClass(CatalogIdentityRequest.class);
        verify(catalog).findOrCreateContent(captor.capture());
        return captor.getValue();
    }
}
