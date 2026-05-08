package com.orinuno.jutsu.sync;

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
import com.orinuno.jutsu.model.JutsuTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for the jut.su L1 → L3 bridge (ARCH-0016 P1b Step 1.C). Verifies the {@code
 * JutsuTitle → CatalogIdentityRequest} mapping, the kill-switch property semantics, and the
 * failure-isolation contract (resolver exceptions never propagate to the sync worker).
 */
@ExtendWith(MockitoExtension.class)
class JutsuCatalogIngestionTest {

    @Mock private CatalogPublicApi catalog;

    private OrinunoProperties properties;
    private JutsuCatalogIngestion ingestion;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        // Default: catalog ingestion enabled to exercise the happy paths; individual tests
        // toggle the flag back off when needed.
        properties.getProviders().getJutsu().getSync().getCatalogIngestion().setEnabled(true);
        ingestion = new JutsuCatalogIngestion(catalog, properties);
    }

    @Test
    @DisplayName("catalog ingestion disabled by property → no resolver call regardless of input")
    void disabledKillSwitchSkipsResolver() {
        properties.getProviders().getJutsu().getSync().getCatalogIngestion().setEnabled(false);

        ingestion.ingest(JutsuTitle.builder().slug("naruto").title("Наруто").build());

        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName("null / blank slug is a no-op even when ingestion is enabled")
    void nullOrBlankSlugIsNoop() {
        ingestion.ingest(null);
        ingestion.ingest(JutsuTitle.builder().slug("").title("X").build());
        ingestion.ingest(JutsuTitle.builder().slug("  ").title("X").build());

        verify(catalog, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName(
            "happy path: enabled + valid title → resolver called with (JUTSU, slug) +"
                    + " titleRu/titleEn/kind=ANIME/parsed year")
    void happyPathMapsAllFields() {
        when(catalog.findOrCreateContent(any()))
                .thenReturn(CatalogContent.builder().id(7L).build());

        JutsuTitle title =
                JutsuTitle.builder()
                        .slug("naruto")
                        .title("Наруто")
                        .originalTitle("Naruto")
                        .yearBucket("2002")
                        .build();
        ingestion.ingest(title);

        ArgumentCaptor<CatalogIdentityRequest> req =
                ArgumentCaptor.forClass(CatalogIdentityRequest.class);
        verify(catalog).findOrCreateContent(req.capture());

        assertThat(req.getValue().sourceType()).isEqualTo(CatalogSourceType.JUTSU);
        assertThat(req.getValue().sourceId()).isEqualTo("naruto");
        assertThat(req.getValue().titleRu()).isEqualTo("Наруто");
        assertThat(req.getValue().titleEn()).isEqualTo("Naruto");
        assertThat(req.getValue().kind()).isEqualTo(CatalogContentKind.ANIME);
        assertThat(req.getValue().year()).isEqualTo(2002);
        assertThat(req.getValue().externalIds())
                .as(
                        "jut.su's L1 row carries no Shikimori/MAL/IMDB ids today, so the merge"
                                + " map stays empty until Kodik or another source provides them")
                .isEmpty();
    }

    @Test
    @DisplayName(
            "year parsing: numeric bucket → integer; non-numeric / out-of-range buckets stay null"
                    + " so the canonical year is left untouched until a richer source fills it")
    void yearParsing() {
        assertThat(JutsuCatalogIngestion.parseYear("2020")).isEqualTo(2020);
        assertThat(JutsuCatalogIngestion.parseYear("1995")).isEqualTo(1995);
        assertThat(JutsuCatalogIngestion.parseYear("before2000")).isNull();
        assertThat(JutsuCatalogIngestion.parseYear("ongoing")).isNull();
        assertThat(JutsuCatalogIngestion.parseYear("")).isNull();
        assertThat(JutsuCatalogIngestion.parseYear(null)).isNull();
        assertThat(JutsuCatalogIngestion.parseYear("1700")).isNull(); // out of bounds
        assertThat(JutsuCatalogIngestion.parseYear("3000")).isNull(); // out of bounds
        assertThat(JutsuCatalogIngestion.parseYear("12345")).isNull(); // wrong length
    }

    @Test
    @DisplayName(
            "resolver exception is caught and logged at WARN — sync worker MUST keep walking;"
                    + " ingest() returns normally after the swallow")
    void resolverExceptionIsSwallowed() {
        when(catalog.findOrCreateContent(any()))
                .thenThrow(new RuntimeException("boom from resolver"));

        // Must not throw — sync workers rely on this contract to keep the L1 cache writes
        // independent from L3 hiccups.
        ingestion.ingest(
                JutsuTitle.builder().slug("ok-slug").title("ok").yearBucket("2020").build());

        verify(catalog).findOrCreateContent(any());
    }
}
