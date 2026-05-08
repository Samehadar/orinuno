package com.orinuno.jutsu.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.jutsu.filter.JutsuGenre;
import com.orinuno.jutsu.filter.JutsuSort;
import com.orinuno.jutsu.filter.JutsuType;
import com.orinuno.jutsu.filter.JutsuYear;
import com.orinuno.jutsu.model.JutsuEpisode;
import com.orinuno.jutsu.model.JutsuTitle;
import com.orinuno.jutsu.repository.JutsuEpisodeRepository;
import com.orinuno.jutsu.repository.JutsuFilmRepository;
import com.orinuno.jutsu.repository.JutsuTitleRepository;
import com.orinuno.model.dto.jutsu.JutsuAnimeInfoDto;
import com.orinuno.model.dto.jutsu.JutsuCatalogPageDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JutsuCatalogReadServiceTest {

    @Mock private JutsuTitleRepository titleRepository;
    @Mock private JutsuEpisodeRepository episodeRepository;
    @Mock private JutsuFilmRepository filmRepository;

    private JutsuCatalogReadService service;

    @BeforeEach
    void setUp() {
        service = new JutsuCatalogReadService(titleRepository, episodeRepository, filmRepository);
    }

    @Test
    @DisplayName("findCatalogPage returns empty when total count is 0 (cold cache)")
    void emptyCacheIsCacheMiss() {
        when(titleRepository.countCatalogRows(any(), any(), any())).thenReturn(0L);

        Optional<JutsuCatalogPageDto> result =
                service.findCatalogPage(JutsuCatalogReadService.JutsuCatalogQuery.unfiltered(1));

        assertThat(result).isEmpty();
        verify(titleRepository, never())
                .findCatalogPage(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName(
            "findCatalogPage materialises rows + count into a JutsuCatalogPageDto with hasMore"
                    + " inferred from totalCount")
    void cacheHitMaterialisesPageDto() {
        when(titleRepository.countCatalogRows(any(), any(), any())).thenReturn(75L);
        when(titleRepository.findCatalogPage(any(), any(), any(), any(), eq(30), eq(0)))
                .thenReturn(
                        List.of(cachedRow("naruto", "Наруто", 1), cachedRow("bleach", "Блич", 2)));

        JutsuCatalogPageDto dto =
                service.findCatalogPage(JutsuCatalogReadService.JutsuCatalogQuery.unfiltered(1))
                        .orElseThrow();

        assertThat(dto.page()).isEqualTo(1);
        assertThat(dto.entries()).hasSize(2);
        assertThat(dto.entries().get(0).slug()).isEqualTo("naruto");
        assertThat(dto.entries().get(0).detailUrl()).isEqualTo("https://jut.su/naruto/");
        assertThat(dto.hasMore()).as("75 rows > 1 * 30 — page 1 has more").isTrue();
    }

    @Test
    @DisplayName(
            "filter genre/type/year sets are converted to sorted slug lists before reaching the"
                    + " mapper")
    void filtersConvertToSortedSlugLists() {
        when(titleRepository.countCatalogRows(any(), any(), any())).thenReturn(5L);
        when(titleRepository.findCatalogPage(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(cachedRow("x", "X", 10)));

        JutsuCatalogReadService.JutsuCatalogQuery q =
                new JutsuCatalogReadService.JutsuCatalogQuery(
                        1,
                        Set.of(JutsuGenre.COMEDY, JutsuGenre.ACTION),
                        Set.of(JutsuType.SHONEN),
                        Set.of(JutsuYear.Y_2024, JutsuYear.Y_2025),
                        JutsuSort.BY_NAME);
        service.findCatalogPage(q);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> genresCap = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> typesCap = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> yearsCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> sortCap = ArgumentCaptor.forClass(String.class);
        verify(titleRepository)
                .findCatalogPage(
                        genresCap.capture(),
                        typesCap.capture(),
                        yearsCap.capture(),
                        sortCap.capture(),
                        eq(30),
                        eq(0));
        assertThat(genresCap.getValue())
                .as("genres slug list must be deterministic — sorted alphabetically")
                .containsExactly("action", "comedy");
        assertThat(typesCap.getValue()).containsExactly("shonen");
        assertThat(yearsCap.getValue()).containsExactly("2024", "2025");
        assertThat(sortCap.getValue())
                .as("BY_NAME maps to title-collated ORDER BY")
                .isEqualTo(JutsuCatalogReadService.orderByFor(JutsuSort.BY_NAME));
    }

    @Test
    @DisplayName("orderByFor produces fixed whitelisted SQL fragments per sort enum")
    void orderByForProducesFixedFragments() {
        // BY_RATING uses catalog_position — the sync worker fills it on every full crawl.
        assertThat(JutsuCatalogReadService.orderByFor(JutsuSort.BY_RATING))
                .contains("catalog_position");
        // BY_NAME uses utf8mb4_unicode_ci so Russian sort order matches website rendering.
        assertThat(JutsuCatalogReadService.orderByFor(JutsuSort.BY_NAME))
                .contains("title COLLATE utf8mb4_unicode_ci");
        // Every fragment ends with `slug ASC` for stable pagination.
        for (JutsuSort sort : JutsuSort.values()) {
            assertThat(JutsuCatalogReadService.orderByFor(sort))
                    .as(
                            "%s must end with `slug ASC` to break ties deterministically across"
                                    + " paginated requests",
                            sort.name())
                    .endsWith("slug ASC");
        }
    }

    @Test
    @DisplayName("findAnimeInfo returns empty when the slug is missing from L1")
    void missingSlugIsCacheMiss() {
        when(titleRepository.findBySlug("ghost")).thenReturn(Optional.empty());

        assertThat(service.findAnimeInfo("ghost")).isEmpty();
        verify(episodeRepository, never()).findBySlug(any());
    }

    @Test
    @DisplayName(
            "findAnimeInfo returns empty when the row is a placeholder (info_fetched_at null) so"
                    + " the caller falls back to live")
    void placeholderRowIsCacheMiss() {
        JutsuTitle placeholder =
                JutsuTitle.builder()
                        .slug("brand-new")
                        .title("Бренд новое")
                        .infoFetchedAt(null)
                        .build();
        when(titleRepository.findBySlug("brand-new")).thenReturn(Optional.of(placeholder));

        assertThat(service.findAnimeInfo("brand-new")).isEmpty();
        verify(episodeRepository, never()).findBySlug(any());
    }

    @Test
    @DisplayName(
            "findAnimeInfo materialises a fully-hydrated row + episode list into a"
                    + " JutsuAnimeInfoDto, grouping episodes by season")
    void hydratedRowMaterialisesAnimeInfoDto() {
        LocalDateTime now = LocalDateTime.now();
        JutsuTitle row =
                JutsuTitle.builder()
                        .slug("onepuunchman")
                        .title("Ванпанчмен")
                        .originalTitle("One Punch Man")
                        .synopsis("synopsis")
                        .thumbnailUrl("thumb.jpg")
                        .yearBucket("2015-2023")
                        .genresCsv("action,comedy")
                        .typesCsv("shonen,superpower")
                        .infoFetchedAt(now)
                        .firstSeenAt(now)
                        .lastSeenAt(now)
                        .build();
        when(titleRepository.findBySlug("onepuunchman")).thenReturn(Optional.of(row));
        when(episodeRepository.findBySlug("onepuunchman"))
                .thenReturn(
                        List.of(
                                episode("onepuunchman", 2, 1),
                                episode("onepuunchman", 1, 1),
                                episode("onepuunchman", 1, 2)));

        JutsuAnimeInfoDto dto = service.findAnimeInfo("onepuunchman").orElseThrow();

        assertThat(dto.slug()).isEqualTo("onepuunchman");
        assertThat(dto.title()).isEqualTo("Ванпанчмен");
        assertThat(dto.originalTitle()).isEqualTo("One Punch Man");
        assertThat(dto.synopsis()).isEqualTo("synopsis");
        assertThat(dto.year()).isEqualTo("2015-2023");
        assertThat(dto.genres()).containsExactly("action", "comedy");
        assertThat(dto.types()).containsExactly("shonen", "superpower");
        assertThat(dto.totalEpisodeCount()).isEqualTo(3);
        assertThat(dto.seasons()).hasSize(2);
        assertThat(dto.seasons().get(0).index()).isEqualTo(1);
        assertThat(dto.seasons().get(0).episodes()).hasSize(2);
        assertThat(dto.seasons().get(1).index()).isEqualTo(2);
        assertThat(dto.seasons().get(1).episodes()).hasSize(1);
    }

    private static JutsuTitle cachedRow(String slug, String title, int catalogPosition) {
        return JutsuTitle.builder()
                .slug(slug)
                .title(title)
                .catalogPosition(catalogPosition)
                .firstSeenAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .build();
    }

    private static JutsuEpisode episode(String slug, int season, int episode) {
        return JutsuEpisode.builder()
                .slug(slug)
                .season(season)
                .episode(episode)
                .label(episode + " серия")
                .relativeUrl("/" + slug + "/season-" + season + "/episode-" + episode + ".html")
                .discoveredAt(LocalDateTime.now())
                .lastSeenAt(LocalDateTime.now())
                .build();
    }
}
