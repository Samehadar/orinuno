package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.dto.ContentExportDto;
import com.orinuno.repository.ContentRepository;
import com.orinuno.repository.EpisodeVariantRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExportDataServiceTest {

    @Mock private ContentRepository contentRepository;
    @Mock private EpisodeVariantRepository episodeVariantRepository;

    @InjectMocks private ExportDataService exportDataService;

    @Test
    @DisplayName("Should build export DTO with grouped seasons and episodes")
    void shouldBuildExportDto() {
        KodikContent content =
                KodikContent.builder()
                        .id(1L)
                        .type("foreign-serial")
                        .title("Test Serial")
                        .titleOrig("Test Serial Orig")
                        .kinopoiskId("123")
                        .year(2024)
                        .build();

        List<KodikEpisodeVariant> variants =
                List.of(
                        KodikEpisodeVariant.builder()
                                .id(1L)
                                .contentId(1L)
                                .seasonNumber(1)
                                .episodeNumber(1)
                                .translationId(1)
                                .translationTitle("Дубляж")
                                .translationType("voice")
                                .quality("720p")
                                .mp4Link("https://cdn.com/s1e1.mp4")
                                .build(),
                        KodikEpisodeVariant.builder()
                                .id(2L)
                                .contentId(1L)
                                .seasonNumber(1)
                                .episodeNumber(1)
                                .translationId(2)
                                .translationTitle("Субтитры")
                                .translationType("subtitles")
                                .quality("1080p")
                                .mp4Link("https://cdn.com/s1e1_sub.mp4")
                                .build(),
                        KodikEpisodeVariant.builder()
                                .id(3L)
                                .contentId(1L)
                                .seasonNumber(1)
                                .episodeNumber(2)
                                .translationId(1)
                                .translationTitle("Дубляж")
                                .translationType("voice")
                                .quality("720p")
                                .mp4Link("https://cdn.com/s1e2.mp4")
                                .build(),
                        KodikEpisodeVariant.builder()
                                .id(4L)
                                .contentId(1L)
                                .seasonNumber(2)
                                .episodeNumber(1)
                                .translationId(1)
                                .translationTitle("Дубляж")
                                .translationType("voice")
                                .quality("720p")
                                .mp4Link("https://cdn.com/s2e1.mp4")
                                .build());

        when(contentRepository.findById(1L)).thenReturn(Optional.of(content));
        when(episodeVariantRepository.findByContentId(1L)).thenReturn(variants);

        Optional<ContentExportDto> result = exportDataService.getExportData(1L);

        assertThat(result).isPresent();
        ContentExportDto dto = result.get();
        assertThat(dto.title()).isEqualTo("Test Serial");
        assertThat(dto.kinopoiskId()).isEqualTo("123");
        assertThat(dto.seasons()).hasSize(2);

        // Season 1
        ContentExportDto.SeasonExportDto season1 = dto.seasons().get(0);
        assertThat(season1.seasonNumber()).isEqualTo(1);
        assertThat(season1.episodes()).hasSize(2);
        // Episode 1 of Season 1 has 2 variants (voice + subtitles)
        assertThat(season1.episodes().get(0).variants()).hasSize(2);
        // Episode 2 of Season 1 has 1 variant
        assertThat(season1.episodes().get(1).variants()).hasSize(1);

        // Season 2
        ContentExportDto.SeasonExportDto season2 = dto.seasons().get(1);
        assertThat(season2.seasonNumber()).isEqualTo(2);
        assertThat(season2.episodes()).hasSize(1);
    }

    @Test
    @DisplayName("Should filter out variants without mp4 links")
    void shouldFilterVariantsWithoutMp4() {
        KodikContent content = KodikContent.builder().id(1L).type("movie").title("Test").build();

        List<KodikEpisodeVariant> variants =
                List.of(
                        KodikEpisodeVariant.builder()
                                .id(1L)
                                .contentId(1L)
                                .seasonNumber(0)
                                .episodeNumber(0)
                                .translationId(1)
                                .mp4Link("https://cdn.com/v.mp4")
                                .build(),
                        KodikEpisodeVariant.builder()
                                .id(2L)
                                .contentId(1L)
                                .seasonNumber(0)
                                .episodeNumber(0)
                                .translationId(2)
                                .mp4Link(null)
                                .build());

        when(contentRepository.findById(1L)).thenReturn(Optional.of(content));
        when(episodeVariantRepository.findByContentId(1L)).thenReturn(variants);

        ContentExportDto dto = exportDataService.getExportData(1L).orElseThrow();
        assertThat(dto.seasons().get(0).episodes().get(0).variants()).hasSize(1);
    }

    @Test
    @DisplayName("Should return empty for non-existent content")
    void shouldReturnEmptyForNonExistent() {
        when(contentRepository.findById(999L)).thenReturn(Optional.empty());
        assertThat(exportDataService.getExportData(999L)).isEmpty();
    }
}
