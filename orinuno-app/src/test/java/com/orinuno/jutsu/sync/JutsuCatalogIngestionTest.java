package com.orinuno.jutsu.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.contract.source.ContentKindHint;
import com.orinuno.contract.source.SourceCatalogEvent;
import com.orinuno.contract.source.SourceEventEmitter;
import com.orinuno.jutsu.model.JutsuTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit-level coverage for the jut.su L1 → producer-side event bridge (ADR 0017). Verifies the
 * mapping from {@link JutsuTitle} into {@link SourceCatalogEvent.TitleObserved}, the year-parsing
 * fallback, and the kill-switch property semantics. Resolver-failure-isolation is covered by {@code
 * CatalogSinkEventEmitterTest} and the e2e {@code CatalogIngestionIT}.
 */
@ExtendWith(MockitoExtension.class)
class JutsuCatalogIngestionTest {

    @Mock private SourceEventEmitter emitter;

    private OrinunoProperties properties;
    private JutsuCatalogIngestion ingestion;

    @BeforeEach
    void setUp() {
        properties = new OrinunoProperties();
        properties.getProviders().getJutsu().getSync().getCatalogIngestion().setEnabled(true);
        ingestion = new JutsuCatalogIngestion(emitter, properties);
    }

    @Test
    @DisplayName("catalog ingestion disabled by property → no emit regardless of input")
    void disabledKillSwitchSkipsEmitter() {
        properties.getProviders().getJutsu().getSync().getCatalogIngestion().setEnabled(false);

        ingestion.ingest(JutsuTitle.builder().slug("naruto").title("Наруто").build());

        verify(emitter, never()).emit(any());
    }

    @Test
    @DisplayName("null / blank slug is a no-op even when ingestion is enabled")
    void nullOrBlankSlugIsNoop() {
        ingestion.ingest(null);
        ingestion.ingest(JutsuTitle.builder().slug("").title("X").build());
        ingestion.ingest(JutsuTitle.builder().slug("  ").title("X").build());

        verify(emitter, never()).emit(any());
    }

    @Test
    @DisplayName(
            "happy path: enabled + valid title → emitter receives TitleObserved with"
                    + " sourceType=jutsu, kindHint=ANIME, parsed year, ExternalIds.empty")
    void happyPathMapsAllFields() {
        JutsuTitle title =
                JutsuTitle.builder()
                        .slug("naruto")
                        .title("Наруто")
                        .originalTitle("Naruto")
                        .yearBucket("2002")
                        .build();
        ingestion.ingest(title);

        SourceCatalogEvent.TitleObserved event = captureTitleObserved();
        assertThat(event.identifier().sourceType()).isEqualTo("jutsu");
        assertThat(event.identifier().sourceId()).isEqualTo("naruto");
        assertThat(event.info().titleRu()).isEqualTo("Наруто");
        assertThat(event.info().titleEn()).isEqualTo("Naruto");
        assertThat(event.info().kindHint()).isEqualTo(ContentKindHint.ANIME);
        assertThat(event.info().year()).isEqualTo(2002);
        assertThat(event.info().externalIds().isEmpty())
                .as(
                        "jut.su's L1 row carries no Shikimori/MAL/IMDB ids today, so the merge"
                                + " contribution stays empty until Kodik or another source"
                                + " provides them")
                .isTrue();
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
        assertThat(JutsuCatalogIngestion.parseYear("1700")).isNull();
        assertThat(JutsuCatalogIngestion.parseYear("3000")).isNull();
        assertThat(JutsuCatalogIngestion.parseYear("12345")).isNull();
    }

    @Test
    @DisplayName(
            "every emit carries a Provenance pointing at the title's anime-info URL + a"
                    + " non-null fetchedAt (sourced from JutsuTitle.lastSeenAt when present)")
    void provenanceIsAlwaysAttached() {
        ingestion.ingest(JutsuTitle.builder().slug("naruto").title("X").yearBucket("2020").build());

        SourceCatalogEvent.TitleObserved event = captureTitleObserved();
        assertThat(event.provenance().sourceUrl()).contains("jut.su/naruto/");
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
