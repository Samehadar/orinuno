/*
 * CatalogSinkEventEmitterTest — ADR 0021 Block B2 unit coverage.
 *
 * Verifies the emitter:
 *   - calls findOrCreateContent on every event variant that carries SourceContentInfo
 *   - uses the chromeless anchor request for EpisodesUpdated
 *   - upserts one episode_source per variant for MovieDiscovered / SeriesDiscovered /
 *     EpisodesUpdated, with the correct (season, episode, provider, translator_id) tuple
 *   - skips events whose sourceType doesn't map to a CatalogSourceType
 *   - swallows RuntimeExceptions from the resolver/repo (idempotency contract)
 */
package com.orinuno.meter.catalog.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.ExternalIds;
import com.orinuno.contract.source.Provenance;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceContentInfo;
import com.orinuno.contract.source.SourceEpisode;
import com.orinuno.contract.source.SourceEpisodeVariant;
import com.orinuno.contract.source.SourceIdentifier;
import com.orinuno.contract.source.SourceSeason;
import com.orinuno.meter.catalog.api.CatalogIdentityRequest;
import com.orinuno.meter.catalog.api.CatalogPublicApi;
import com.orinuno.meter.catalog.model.CatalogContent;
import com.orinuno.meter.catalog.model.EpisodeSource;
import com.orinuno.meter.catalog.repository.EpisodeSourceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogSinkEventEmitter — L3 chrome + L2 episode_source upserts (ADR 0021 B2)")
class CatalogSinkEventEmitterTest {

    @Mock private CatalogPublicApi catalog;
    @Mock private EpisodeSourceRepository episodeSources;
    @Captor private ArgumentCaptor<CatalogIdentityRequest> requestCaptor;
    @Captor private ArgumentCaptor<EpisodeSource> sourceCaptor;

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-12T00:00:00Z"), ZoneOffset.UTC);
    private CatalogSinkEventEmitter emitter;

    @BeforeEach
    void setUp() {
        emitter = new CatalogSinkEventEmitter(catalog, episodeSources, clock);
    }

    @Test
    @DisplayName("TitleObserved resolves canonical row + does NOT touch episode_source")
    void titleObservedSkipsL2() {
        when(catalog.findOrCreateContent(any())).thenReturn(content(42L));

        emitter.emit(
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("kodik", "123"), info("Title"), provenance()));

        verify(catalog).findOrCreateContent(any());
        verify(episodeSources, never()).upsert(any());
    }

    @Test
    @DisplayName("MovieDiscovered upserts one episode_source row at season=0 episode=1")
    void movieDiscoveredWritesOneSource() {
        when(catalog.findOrCreateContent(any())).thenReturn(content(7L));

        SourceEpisodeVariant variant =
                new SourceEpisodeVariant(
                        SourceIdentifier.of("kodik", "v-100"),
                        "https://kodik.cc/movie/100/abc/720p",
                        "Anime Translation",
                        "720p",
                        null,
                        null);

        emitter.emit(
                new SourceCatalogEvent.MovieDiscovered(
                        SourceIdentifier.of("kodik", "7"), info("Film"), variant, provenance()));

        verify(episodeSources).upsert(sourceCaptor.capture());
        EpisodeSource captured = sourceCaptor.getValue();
        assertThat(captured.getContentId()).isEqualTo(7L);
        assertThat(captured.getSeason()).isZero();
        assertThat(captured.getEpisode()).isEqualTo(1);
        assertThat(captured.getProvider()).isEqualTo("KODIK");
        assertThat(captured.getTranslatorId()).isEqualTo("v-100");
        assertThat(captured.getTranslatorName()).isEqualTo("Anime Translation");
        assertThat(captured.getSourceUrl()).isEqualTo("https://kodik.cc/movie/100/abc/720p");
    }

    @Test
    @DisplayName("SeriesDiscovered upserts one episode_source per (season, episode, variant)")
    void seriesDiscoveredFansOutVariants() {
        when(catalog.findOrCreateContent(any())).thenReturn(content(11L));

        SourceEpisodeVariant v1s1e1 = variant("kodik", "v-1", "iframe/1");
        SourceEpisodeVariant v2s1e1 = variant("kodik", "v-2", "iframe/2");
        SourceEpisodeVariant v1s1e2 = variant("kodik", "v-3", "iframe/3");

        SourceCatalogEvent.SeriesDiscovered event =
                new SourceCatalogEvent.SeriesDiscovered(
                        SourceIdentifier.of("kodik", "11"),
                        info("Series"),
                        List.of(
                                new SourceSeason(
                                        null,
                                        null,
                                        null,
                                        1,
                                        List.of(
                                                new SourceEpisode(
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        1,
                                                        List.of(v1s1e1, v2s1e1)),
                                                new SourceEpisode(
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        2,
                                                        List.of(v1s1e2))))),
                        provenance());

        emitter.emit(event);

        verify(episodeSources, times(3)).upsert(any());
    }

    @Test
    @DisplayName("EpisodesUpdated resolves canonical row from anchor only (no chrome request)")
    void episodesUpdatedUsesAnchorRequest() {
        when(catalog.findOrCreateContent(any())).thenReturn(content(99L));

        SourceEpisodeVariant v = variant("jutsu", "slug/s1/e1", "https://jut.su/anime/x/1/1.html");
        SourceCatalogEvent.EpisodesUpdated event =
                new SourceCatalogEvent.EpisodesUpdated(
                        SourceIdentifier.of("jutsu", "slug"),
                        List.of(
                                new SourceSeason(
                                        null,
                                        null,
                                        null,
                                        1,
                                        List.of(
                                                new SourceEpisode(
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        1,
                                                        List.of(v))))),
                        provenance());

        emitter.emit(event);

        verify(catalog).findOrCreateContent(requestCaptor.capture());
        CatalogIdentityRequest req = requestCaptor.getValue();
        assertThat(req.sourceId()).isEqualTo("slug");
        assertThat(req.titleRu()).isNull();
        assertThat(req.titleEn()).isNull();
        assertThat(req.year()).isNull();
        verify(episodeSources, times(1)).upsert(any());
    }

    @Test
    @DisplayName("Unknown sourceType is logged + skipped, no calls into catalog or episode_source")
    void unknownSourceTypeSkipped() {
        emitter.emit(
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("sibnet", "abc"), info("X"), provenance()));

        verify(catalog, never()).findOrCreateContent(any());
        verify(episodeSources, never()).upsert(any());
    }

    @Test
    @DisplayName("SourceRemoved is a no-op (deferred soft-removal)")
    void sourceRemovedNoOp() {
        emitter.emit(
                new SourceCatalogEvent.SourceRemoved(
                        SourceIdentifier.of("kodik", "5"), provenance()));

        verify(catalog, never()).findOrCreateContent(any());
        verify(episodeSources, never()).upsert(any());
    }

    @Test
    @DisplayName("RuntimeException from catalog resolver does not propagate")
    void resolverExceptionSwallowed() {
        when(catalog.findOrCreateContent(any())).thenThrow(new RuntimeException("db deadlock"));

        emitter.emit(
                new SourceCatalogEvent.TitleObserved(
                        SourceIdentifier.of("kodik", "1"), info("X"), provenance()));
        // no throw → contract satisfied
    }

    @Test
    @DisplayName(
            "RuntimeException from episode_source upsert does not propagate; subsequent variant"
                    + " still tried")
    void upsertExceptionSwallowed() {
        when(catalog.findOrCreateContent(any())).thenReturn(content(1L));
        org.mockito.Mockito.doThrow(new RuntimeException("dup key"))
                .when(episodeSources)
                .upsert(any());

        SourceEpisodeVariant v1 = variant("kodik", "v-1", "u1");
        SourceEpisodeVariant v2 = variant("kodik", "v-2", "u2");
        emitter.emit(
                new SourceCatalogEvent.SeriesDiscovered(
                        SourceIdentifier.of("kodik", "1"),
                        info("S"),
                        List.of(
                                new SourceSeason(
                                        null,
                                        null,
                                        null,
                                        1,
                                        List.of(
                                                new SourceEpisode(
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        1,
                                                        List.of(v1, v2))))),
                        provenance()));

        verify(episodeSources, times(2)).upsert(any());
    }

    private static CatalogContent content(long id) {
        return CatalogContent.builder().id(id).build();
    }

    private static SourceContentInfo info(String titleRu) {
        return SourceContentInfo.builder()
                .titleRu(titleRu)
                .kindHint(ContentKindHint.ANIME)
                .externalIds(ExternalIds.builder().build())
                .build();
    }

    private static SourceEpisodeVariant variant(String sourceType, String sourceId, String url) {
        return new SourceEpisodeVariant(
                SourceIdentifier.of(sourceType, sourceId), url, null, null, null, null);
    }

    private static Provenance provenance() {
        return Provenance.of("https://example/source-url", Instant.parse("2026-05-12T00:00:00Z"));
    }
}
