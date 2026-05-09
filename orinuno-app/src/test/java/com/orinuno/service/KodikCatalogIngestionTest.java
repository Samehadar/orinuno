package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceEventEmitter;
import com.orinuno.model.KodikContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for the Kodik L1 → producer-side event bridge (ADR 0017). Verifies the
 * mapping from {@link KodikContent} into {@link SourceCatalogEvent.TitleObserved}, the
 * kind-translation logic for Kodik's free-form {@code type} string, and the kill-switch property
 * semantics. The translation from event into the catalog's internal {@code CatalogIdentityRequest}
 * and the resolver-failure-isolation contract live one layer further in {@code
 * CatalogSinkEventEmitter} and are covered by {@code CatalogSinkEventEmitterTest} and {@code
 * CatalogIngestionIT}.
 */
@ExtendWith(MockitoExtension.class)
class KodikCatalogIngestionTest {

    @Mock private SourceEventEmitter emitter;

    private OrinunoProperties properties;
    private KodikCatalogIngestion ingestion;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getKodik().getCatalogIngestion().setEnabled(true);
        ingestion = new KodikCatalogIngestion(emitter, properties);
    }

    @Test
    @DisplayName(
            "happy path: enabled + KodikContent with kodikId + external ids → emitter receives"
                    + " TitleObserved with sourceType=kodik, all external ids preserved on"
                    + " ExternalIds")
    void happyPathPropagatesAllExternalIds() {
        KodikContent content =
                KodikContent.builder()
                        .id(11L)
                        .kodikId("movie-12345")
                        .type("anime")
                        .title("Наруто")
                        .titleOrig("Naruto")
                        .year(2002)
                        .shikimoriId("1")
                        .imdbId("tt0409591")
                        .kinopoiskId("283290")
                        .build();

        ingestion.ingest(content);

        SourceCatalogEvent.TitleObserved event = captureTitleObserved();
        assertThat(event.identifier().sourceType()).isEqualTo("kodik");
        assertThat(event.identifier().sourceId()).isEqualTo("movie-12345");
        assertThat(event.info().titleRu()).isEqualTo("Наруто");
        assertThat(event.info().titleEn()).isEqualTo("Naruto");
        assertThat(event.info().kindHint()).isEqualTo(ContentKindHint.ANIME);
        assertThat(event.info().year()).isEqualTo(2002);
        assertThat(event.info().externalIds().shikimoriId()).isEqualTo("1");
        assertThat(event.info().externalIds().imdbId()).isEqualTo("tt0409591");
        assertThat(event.info().externalIds().kinopoiskId()).isEqualTo("283290");
        assertThat(event.info().externalIds().malId()).isNull();
    }

    @Test
    @DisplayName("kill-switch disabled → no emit regardless of input")
    void killSwitchSkipsEmitter() {
        properties.getKodik().getCatalogIngestion().setEnabled(false);

        ingestion.ingest(KodikContent.builder().id(99L).kodikId("movie-1").type("anime").build());

        verify(emitter, never()).emit(any());
    }

    @Test
    @DisplayName(
            "no usable sourceId (no kodikId AND no kinopoiskId) → skip ingestion silently rather"
                    + " than build a binding the resolver could never reverse-look-up")
    void skipsIngestionWhenNoSourceIdAvailable() {
        ingestion.ingest(KodikContent.builder().id(50L).type("anime").build());

        verify(emitter, never()).emit(any());
    }

    @Test
    @DisplayName(
            "fallback sourceId: missing kodikId → kp:<kinopoiskId> synthesis so partially-ingested"
                    + " rows still get a binding")
    void fallbackSourceIdToKinopoiskWhenKodikIdMissing() {
        KodikContent content =
                KodikContent.builder().id(60L).kinopoiskId("283290").type("foreign-movie").build();

        ingestion.ingest(content);

        SourceCatalogEvent.TitleObserved event = captureTitleObserved();
        assertThat(event.identifier().sourceId()).isEqualTo("kp:283290");
    }

    @Test
    @DisplayName(
            "kind mapping: anime / anime-serial → ANIME; *-serial → SERIES; movie / film → MOVIE;"
                    + " unknown / blank / null → UNKNOWN")
    void kindMapping() {
        assertThat(KodikCatalogIngestion.mapKind("anime")).isEqualTo(ContentKindHint.ANIME);
        assertThat(KodikCatalogIngestion.mapKind("anime-serial")).isEqualTo(ContentKindHint.ANIME);
        assertThat(KodikCatalogIngestion.mapKind("foreign-serial"))
                .isEqualTo(ContentKindHint.SERIES);
        assertThat(KodikCatalogIngestion.mapKind("documentary-serial"))
                .isEqualTo(ContentKindHint.SERIES);
        assertThat(KodikCatalogIngestion.mapKind("cartoon-serial"))
                .isEqualTo(ContentKindHint.SERIES);
        assertThat(KodikCatalogIngestion.mapKind("foreign-movie")).isEqualTo(ContentKindHint.MOVIE);
        assertThat(KodikCatalogIngestion.mapKind("russian-movie")).isEqualTo(ContentKindHint.MOVIE);
        assertThat(KodikCatalogIngestion.mapKind("film")).isEqualTo(ContentKindHint.MOVIE);
        assertThat(KodikCatalogIngestion.mapKind("documentary")).isEqualTo(ContentKindHint.UNKNOWN);
        assertThat(KodikCatalogIngestion.mapKind(null)).isEqualTo(ContentKindHint.UNKNOWN);
        assertThat(KodikCatalogIngestion.mapKind("  ")).isEqualTo(ContentKindHint.UNKNOWN);
    }

    @Test
    @DisplayName(
            "blank external ids are normalised away by ExternalIds (NON_NULL contract) — emit"
                    + " carries only populated ids")
    void blankExternalIdsAreStripped() {
        KodikContent content =
                KodikContent.builder()
                        .kodikId("movie-x")
                        .type("anime")
                        .shikimoriId("")
                        .imdbId("   ")
                        .kinopoiskId("283290")
                        .build();

        ingestion.ingest(content);

        SourceCatalogEvent.TitleObserved event = captureTitleObserved();
        assertThat(event.info().externalIds().shikimoriId()).isNull();
        assertThat(event.info().externalIds().imdbId()).isNull();
        assertThat(event.info().externalIds().kinopoiskId()).isEqualTo("283290");
    }

    @Test
    @DisplayName("null KodikContent is a no-op (defensive)")
    void nullContentIsNoop() {
        ingestion.ingest(null);
        verify(emitter, never()).emit(any());
    }

    @Test
    @DisplayName(
            "every emit carries a Provenance with non-blank sourceUrl + fetchedAt — required by"
                    + " the contract; downstream OSS consumers persist it for drift dashboards")
    void provenanceIsAlwaysAttached() {
        ingestion.ingest(
                KodikContent.builder().kodikId("movie-x").type("anime").title("X").build());

        SourceCatalogEvent.TitleObserved event = captureTitleObserved();
        assertThat(event.provenance().sourceUrl()).isNotBlank();
        assertThat(event.provenance().fetchedAt()).isNotNull();
    }

    private SourceCatalogEvent.TitleObserved captureTitleObserved() {
        ArgumentCaptor<SourceCatalogEvent> captor =
                ArgumentCaptor.forClass(SourceCatalogEvent.class);
        verify(emitter).emit(captor.capture());
        SourceCatalogEvent value = captor.getValue();
        assertThat(value).isInstanceOf(SourceCatalogEvent.TitleObserved.class);
        return (SourceCatalogEvent.TitleObserved) value;
    }
}
