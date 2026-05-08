package com.orinuno.service;

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
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for the Kodik L1 → L3 bridge (ARCH-0016 P1b Step 1.C.B). Covers the mapping
 * from {@link KodikContent} into {@link CatalogIdentityRequest}, the kind-translation logic for
 * Kodik's free-form {@code type} string, the kill-switch property semantics, and the
 * failure-isolation contract that the {@code ContentService} write path relies on.
 */
@ExtendWith(MockitoExtension.class)
class KodikCatalogIngestionTest {

    @Mock private CatalogPublicApi catalog;

    private OrinunoProperties properties;
    private KodikCatalogIngestion ingestion;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getKodik().getCatalogIngestion().setEnabled(true);
        ingestion = new KodikCatalogIngestion(catalog, properties);
    }

    @Test
    @DisplayName(
            "happy path: enabled + KodikContent with kodikId + external ids → resolver called"
                    + " with KODIK source + external-db ids preserved")
    void happyPathPropagatesAllExternalIds() {
        when(catalog.findOrCreateContent(any()))
                .thenReturn(CatalogContent.builder().id(7L).build());

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

        ArgumentCaptor<CatalogIdentityRequest> req =
                ArgumentCaptor.forClass(CatalogIdentityRequest.class);
        verify(catalog).findOrCreateContent(req.capture());
        assertThat(req.getValue().sourceType()).isEqualTo(CatalogSourceType.KODIK);
        assertThat(req.getValue().sourceId()).isEqualTo("movie-12345");
        assertThat(req.getValue().titleRu()).isEqualTo("Наруто");
        assertThat(req.getValue().titleEn()).isEqualTo("Naruto");
        assertThat(req.getValue().kind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(req.getValue().year()).isEqualTo(2002);
        assertThat(req.getValue().externalId(CatalogSourceType.SHIKIMORI)).contains("1");
        assertThat(req.getValue().externalId(CatalogSourceType.IMDB)).contains("tt0409591");
        assertThat(req.getValue().externalId(CatalogSourceType.KINOPOISK)).contains("283290");
        assertThat(req.getValue().externalId(CatalogSourceType.MAL)).isEmpty();
    }

    @Test
    @DisplayName("kill-switch disabled → no resolver call regardless of input")
    void killSwitchSkipsResolver() {
        properties.getKodik().getCatalogIngestion().setEnabled(false);

        ingestion.ingest(KodikContent.builder().id(99L).kodikId("movie-1").type("anime").build());

        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName(
            "no usable sourceId (no kodikId AND no kinopoiskId) → skip ingestion silently rather"
                    + " than build a binding the resolver could never reverse-look-up")
    void skipsIngestionWhenNoSourceIdAvailable() {
        ingestion.ingest(KodikContent.builder().id(50L).type("anime").build());

        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName(
            "fallback sourceId: missing kodikId → kp:<kinopoiskId> synthesis so partially-ingested"
                    + " rows still get a binding")
    void fallbackSourceIdToKinopoiskWhenKodikIdMissing() {
        when(catalog.findOrCreateContent(any())).thenReturn(CatalogContent.builder().build());

        KodikContent content =
                KodikContent.builder().id(60L).kinopoiskId("283290").type("foreign-movie").build();

        ingestion.ingest(content);

        ArgumentCaptor<CatalogIdentityRequest> req =
                ArgumentCaptor.forClass(CatalogIdentityRequest.class);
        verify(catalog).findOrCreateContent(req.capture());
        assertThat(req.getValue().sourceId()).isEqualTo("kp:283290");
    }

    @Test
    @DisplayName(
            "kind mapping: anime / anime-serial → ANIME; *-serial → SERIES; movie / film → MOVIE;"
                    + " unknown / blank / null → UNKNOWN")
    void kindMapping() {
        assertThat(KodikCatalogIngestion.mapKind("anime")).isEqualTo(CatalogContentKind.ANIME);
        assertThat(KodikCatalogIngestion.mapKind("anime-serial"))
                .isEqualTo(CatalogContentKind.ANIME);
        assertThat(KodikCatalogIngestion.mapKind("foreign-serial"))
                .isEqualTo(CatalogContentKind.SERIES);
        assertThat(KodikCatalogIngestion.mapKind("documentary-serial"))
                .isEqualTo(CatalogContentKind.SERIES);
        assertThat(KodikCatalogIngestion.mapKind("cartoon-serial"))
                .isEqualTo(CatalogContentKind.SERIES);
        assertThat(KodikCatalogIngestion.mapKind("foreign-movie"))
                .isEqualTo(CatalogContentKind.MOVIE);
        assertThat(KodikCatalogIngestion.mapKind("russian-movie"))
                .isEqualTo(CatalogContentKind.MOVIE);
        assertThat(KodikCatalogIngestion.mapKind("film")).isEqualTo(CatalogContentKind.MOVIE);
        assertThat(KodikCatalogIngestion.mapKind("documentary"))
                .isEqualTo(CatalogContentKind.UNKNOWN);
        assertThat(KodikCatalogIngestion.mapKind(null)).isEqualTo(CatalogContentKind.UNKNOWN);
        assertThat(KodikCatalogIngestion.mapKind("  ")).isEqualTo(CatalogContentKind.UNKNOWN);
    }

    @Test
    @DisplayName(
            "blank external ids are stripped from the request so the resolver doesn't probe with"
                    + " empty strings (anchor lookup would never match)")
    void blankExternalIdsAreStripped() {
        when(catalog.findOrCreateContent(any())).thenReturn(CatalogContent.builder().build());

        KodikContent content =
                KodikContent.builder()
                        .kodikId("movie-x")
                        .type("anime")
                        .shikimoriId("")
                        .imdbId("   ")
                        .kinopoiskId("283290")
                        .build();

        ingestion.ingest(content);

        ArgumentCaptor<CatalogIdentityRequest> req =
                ArgumentCaptor.forClass(CatalogIdentityRequest.class);
        verify(catalog).findOrCreateContent(req.capture());
        assertThat(req.getValue().externalId(CatalogSourceType.SHIKIMORI)).isEmpty();
        assertThat(req.getValue().externalId(CatalogSourceType.IMDB)).isEmpty();
        assertThat(req.getValue().externalId(CatalogSourceType.KINOPOISK)).contains("283290");
    }

    @Test
    @DisplayName(
            "resolver exception is caught and logged WARN — ContentService.findOrCreateContent"
                    + " never sees the failure, the kodik_content row stays committed")
    void resolverExceptionIsSwallowed() {
        when(catalog.findOrCreateContent(any()))
                .thenThrow(new RuntimeException("boom from resolver"));

        ingestion.ingest(KodikContent.builder().kodikId("movie-x").type("anime").build());

        verify(catalog).findOrCreateContent(any());
        // No throw.
    }

    @Test
    @DisplayName("null KodikContent is a no-op (defensive)")
    void nullContentIsNoop() {
        ingestion.ingest(null);
        verify(catalog, never()).findOrCreateContent(any());
    }
}
