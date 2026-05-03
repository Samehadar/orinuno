package com.orinuno.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.orinuno.model.EpisodeSource;
import com.orinuno.model.EpisodeVideo;
import com.orinuno.model.dto.ContentDto;
import com.orinuno.repository.EpisodeSourceRepository;
import com.orinuno.repository.EpisodeVideoRepository;
import com.orinuno.service.ContentService;
import com.orinuno.service.orchestration.MultiSourceRanker;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.reactive.server.WebTestClient;

@ExtendWith(MockitoExtension.class)
class MultiSourceControllerTest {

    @Mock private EpisodeSourceRepository sourceRepository;
    @Mock private EpisodeVideoRepository videoRepository;
    @Mock private ContentService contentService;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        MultiSourceController controller =
                new MultiSourceController(
                        sourceRepository, videoRepository, new MultiSourceRanker(), contentService);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /api/v1/anime/{contentId}/episodes/{s}/{e}/sources returns ranked candidates")
    void canonicalAnimePathReturnsRanked() {
        EpisodeSource src =
                EpisodeSource.builder()
                        .id(11L)
                        .contentId(42L)
                        .season(1)
                        .episode(2)
                        .translatorId("t1")
                        .translatorName("AniLibria")
                        .provider("KODIK")
                        .build();
        EpisodeVideo vid =
                EpisodeVideo.builder()
                        .id(101L)
                        .sourceId(11L)
                        .quality("720")
                        .videoUrl("https://hls/720/master.m3u8")
                        .videoFormat("application/x-mpegURL")
                        .decodedAt(LocalDateTime.now())
                        .decodeMethod("REGEX")
                        .decodeFailedCount(0)
                        .build();
        when(sourceRepository.findByEpisode(eq(42L), eq(1), eq(2))).thenReturn(List.of(src));
        when(videoRepository.findBySource(eq(11L))).thenReturn(List.of(vid));

        client.get()
                .uri("/api/v1/anime/42/episodes/1/2/sources")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.contentId")
                .isEqualTo(42)
                .jsonPath("$.season")
                .isEqualTo(1)
                .jsonPath("$.episode")
                .isEqualTo(2)
                .jsonPath("$.count")
                .isEqualTo(1)
                .jsonPath("$.candidates[0].provider")
                .isEqualTo("KODIK")
                .jsonPath("$.candidates[0].quality")
                .isEqualTo("720");
    }

    @Test
    @DisplayName(
            "GET /api/v1/anime/{contentId}/episodes/{s}/{e}/sources returns empty candidates when"
                    + " no sources are stored")
    void canonicalAnimePathReturnsEmptyWhenNoSources() {
        when(sourceRepository.findByEpisode(eq(42L), eq(5), eq(7))).thenReturn(List.of());

        client.get()
                .uri("/api/v1/anime/42/episodes/5/7/sources")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.count")
                .isEqualTo(0)
                .jsonPath("$.candidates")
                .isArray();
    }

    @Test
    @DisplayName(
            "GET /api/v1/anime/by-kinopoisk/{kpId}/episodes/{s}/{e}/sources looks up content then"
                    + " returns ranked candidates")
    void byKinopoiskPathLooksUpContentThenRanks() {
        ContentDto content = ContentDto.builder().id(99L).kinopoiskId("123456").build();
        when(contentService.findByKinopoiskId(eq("123456"))).thenReturn(Optional.of(content));
        EpisodeSource src =
                EpisodeSource.builder()
                        .id(1L)
                        .contentId(99L)
                        .season(2)
                        .episode(3)
                        .provider("ANIBOOM")
                        .build();
        EpisodeVideo vid =
                EpisodeVideo.builder()
                        .id(2L)
                        .sourceId(1L)
                        .quality("1080")
                        .videoUrl("https://aniboom/1080.mp4")
                        .videoFormat("video/mp4")
                        .decodedAt(LocalDateTime.now())
                        .build();
        when(sourceRepository.findByEpisode(eq(99L), eq(2), eq(3))).thenReturn(List.of(src));
        when(videoRepository.findBySource(eq(1L))).thenReturn(List.of(vid));

        client.get()
                .uri("/api/v1/anime/by-kinopoisk/123456/episodes/2/3/sources")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.contentId")
                .isEqualTo(99)
                .jsonPath("$.candidates[0].provider")
                .isEqualTo("ANIBOOM")
                .jsonPath("$.candidates[0].quality")
                .isEqualTo("1080");
    }

    @Test
    @DisplayName(
            "GET /api/v1/anime/by-kinopoisk/... returns 404 when no kodik_content matches the id")
    void byKinopoiskPathReturns404WhenContentMissing() {
        when(contentService.findByKinopoiskId(eq("nonexistent"))).thenReturn(Optional.empty());

        client.get()
                .uri("/api/v1/anime/by-kinopoisk/nonexistent/episodes/1/1/sources")
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody()
                .jsonPath("$.error")
                .isEqualTo("kinopoiskId not found")
                .jsonPath("$.kinopoiskId")
                .isEqualTo("nonexistent");

        verifyNoInteractions(sourceRepository, videoRepository);
    }

    @Test
    @DisplayName(
            "Legacy GET /api/v1/sources/{contentId}/{s}/{e} keeps working as a deprecated alias")
    void legacyShortPathStillWorks() {
        EpisodeSource src =
                EpisodeSource.builder()
                        .id(7L)
                        .contentId(42L)
                        .season(1)
                        .episode(1)
                        .provider("JUTSU")
                        .build();
        EpisodeVideo vid =
                EpisodeVideo.builder()
                        .id(8L)
                        .sourceId(7L)
                        .quality("720")
                        .videoUrl("https://jutsu/720.mp4")
                        .videoFormat("video/mp4")
                        .decodedAt(LocalDateTime.now())
                        .build();
        when(sourceRepository.findByEpisode(eq(42L), eq(1), eq(1))).thenReturn(List.of(src));
        when(videoRepository.findBySource(eq(7L))).thenReturn(List.of(vid));

        client.get()
                .uri("/api/v1/sources/42/1/1")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.candidates[0].provider")
                .isEqualTo("JUTSU");
    }

    @Test
    @DisplayName("?prefer=ANIBOOM,KODIK overrides default provider order")
    void preferQueryParamOverridesProviderOrder() {
        EpisodeSource kodik =
                EpisodeSource.builder()
                        .id(1L)
                        .contentId(42L)
                        .season(1)
                        .episode(1)
                        .provider("KODIK")
                        .build();
        EpisodeSource aniboom =
                EpisodeSource.builder()
                        .id(2L)
                        .contentId(42L)
                        .season(1)
                        .episode(1)
                        .provider("ANIBOOM")
                        .build();
        LocalDateTime now = LocalDateTime.now();
        EpisodeVideo kodikVid =
                EpisodeVideo.builder()
                        .id(10L)
                        .sourceId(1L)
                        .quality("720")
                        .videoUrl("https://kodik/720.m3u8")
                        .decodedAt(now)
                        .build();
        EpisodeVideo aniboomVid =
                EpisodeVideo.builder()
                        .id(20L)
                        .sourceId(2L)
                        .quality("720")
                        .videoUrl("https://aniboom/720.mp4")
                        .decodedAt(now)
                        .build();
        when(sourceRepository.findByEpisode(eq(42L), eq(1), eq(1)))
                .thenReturn(List.of(kodik, aniboom));
        when(videoRepository.findBySource(eq(1L))).thenReturn(List.of(kodikVid));
        when(videoRepository.findBySource(eq(2L))).thenReturn(List.of(aniboomVid));

        client.get()
                .uri("/api/v1/anime/42/episodes/1/1/sources?prefer=ANIBOOM,KODIK")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.candidates[0].provider")
                .isEqualTo("ANIBOOM")
                .jsonPath("$.candidates[1].provider")
                .isEqualTo("KODIK");
    }
}
