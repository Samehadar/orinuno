package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.orinuno.client.KodikApiClient;
import com.orinuno.client.dto.KodikSearchResponse;
import com.orinuno.configuration.OrinunoProperties;
import com.orinuno.model.KodikContent;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.model.dto.ParseRequestDto;
import com.orinuno.repository.EpisodeVariantRepository;
import com.orinuno.service.metrics.KodikCdnHostMetrics;
import com.orinuno.service.metrics.KodikDecoderMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ParserServiceTest {

    @Mock private KodikApiClient kodikApiClient;
    @Mock private ContentService contentService;
    @Mock private KodikVideoDecoderService decoderService;
    @Mock private EpisodeVariantRepository episodeVariantRepository;

    private ParserService parserService;
    private KodikCdnHostMetrics kodikCdnHostMetrics;
    private KodikDecoderMetrics decoderMetrics;

    @BeforeEach
    void setUp() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getKodik().setRequestDelayMs(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        kodikCdnHostMetrics = new KodikCdnHostMetrics(registry);
        decoderMetrics = new KodikDecoderMetrics(registry);
        parserService =
                new ParserService(
                        kodikApiClient,
                        contentService,
                        decoderService,
                        episodeVariantRepository,
                        properties,
                        kodikCdnHostMetrics,
                        decoderMetrics);
    }

    @Test
    @DisplayName("Should process search results and save content")
    void shouldProcessSearchResults() {
        KodikSearchResponse.Result result =
                KodikSearchResponse.Result.builder()
                        .id("movie-1")
                        .type("foreign-movie")
                        .title("Test")
                        .kinopoiskId("123")
                        .translation(
                                KodikSearchResponse.Translation.builder()
                                        .id(1)
                                        .title("Дубляж")
                                        .type("voice")
                                        .build())
                        .build();

        KodikSearchResponse response =
                KodikSearchResponse.builder().total(1).results(List.of(result)).build();

        KodikContent savedContent = KodikContent.builder().id(1L).title("Test").build();

        when(kodikApiClient.search(any())).thenReturn(Mono.just(response));
        when(contentService.findOrCreateContent(any())).thenReturn(savedContent);
        doNothing().when(contentService).saveVariants(any());

        ParseRequestDto request =
                ParseRequestDto.builder().kinopoiskId("123").decodeLinks(false).build();

        StepVerifier.create(parserService.search(request))
                .assertNext(
                        contents -> {
                            assertThat(contents).hasSize(1);
                            assertThat(contents.get(0).getTitle()).isEqualTo("Test");
                        })
                .verifyComplete();

        verify(contentService).findOrCreateContent(any());
        verify(contentService).saveVariants(any());
    }

    @Test
    @DisplayName("Should handle empty search results")
    void shouldHandleEmptyResults() {
        KodikSearchResponse response =
                KodikSearchResponse.builder().total(0).results(List.of()).build();

        when(kodikApiClient.search(any())).thenReturn(Mono.just(response));

        ParseRequestDto request = ParseRequestDto.builder().title("Nonexistent").build();

        StepVerifier.create(parserService.search(request))
                .assertNext(contents -> assertThat(contents).isEmpty())
                .verifyComplete();

        verify(contentService, never()).findOrCreateContent(any());
    }

    @Test
    @DisplayName("selectBestQuality returns highest numeric quality URL")
    void selectBestQualityReturnsMaxNumericQuality() {
        Map<String, String> videoLinks = new HashMap<>();
        videoLinks.put("360", "https://cdn.example/360.mp4");
        videoLinks.put("720", "https://cdn.example/720.mp4");
        videoLinks.put("480", "https://cdn.example/480.mp4");

        assertThat(ParserService.selectBestQuality(videoLinks))
                .isEqualTo("https://cdn.example/720.mp4");
    }

    @Test
    @DisplayName("selectBestQuality skips _* sentinel keys (regression: mp4_link='true')")
    void selectBestQualitySkipsSentinelKeys() {
        Map<String, String> videoLinks = new HashMap<>();
        videoLinks.put("_geo_blocked", "true");
        videoLinks.put("720", "https://cdn.example/720.mp4");

        assertThat(ParserService.selectBestQuality(videoLinks))
                .isEqualTo("https://cdn.example/720.mp4");
    }

    @Test
    @DisplayName("selectBestQuality returns null when only sentinel present")
    void selectBestQualityReturnsNullWhenOnlySentinel() {
        Map<String, String> videoLinks = new HashMap<>();
        videoLinks.put("_geo_blocked", "true");

        assertThat(ParserService.selectBestQuality(videoLinks)).isNull();
    }

    @Test
    @DisplayName("selectBestQuality returns null on empty/null map")
    void selectBestQualityHandlesEmptyAndNull() {
        assertThat(ParserService.selectBestQuality(null)).isNull();
        assertThat(ParserService.selectBestQuality(new HashMap<>())).isNull();
    }

    @Test
    @DisplayName("selectBestQuality skips non-http values defensively")
    void selectBestQualitySkipsNonHttpValues() {
        Map<String, String> videoLinks = new HashMap<>();
        videoLinks.put("720", "true");
        videoLinks.put("480", "https://cdn.example/480.mp4");

        assertThat(ParserService.selectBestQuality(videoLinks))
                .isEqualTo("https://cdn.example/480.mp4");
    }

    @Test
    @DisplayName(
            "selectBestQuality skips Kodik's 'default' key per ADR 0004"
                    + " (DECODE-1 hardening: don't pick the bandwidth-conservative default)")
    void selectBestQualityIgnoresDefaultKey() {
        Map<String, String> videoLinks = new HashMap<>();
        videoLinks.put("default", "https://cdn.example/360-default.mp4");
        videoLinks.put("720", "https://cdn.example/720.mp4");

        assertThat(ParserService.selectBestQuality(videoLinks))
                .as(
                        "ADR 0004: Kodik's 'default' field is always the conservative 360p"
                                + " — we always pick the max numeric instead.")
                .isEqualTo("https://cdn.example/720.mp4");
    }

    @Test
    @DisplayName(
            "selectBestQuality is deterministic when only non-numeric and sentinel keys appear"
                    + " (DECODE-1 hardening: returns null instead of arbitrary string entry)")
    void selectBestQualityReturnsNullWhenNoNumericKeys() {
        Map<String, String> videoLinks = new HashMap<>();
        videoLinks.put("default", "https://cdn.example/default.mp4");
        videoLinks.put("auto", "https://cdn.example/auto.mp4");
        videoLinks.put("_geo_blocked", "true");

        assertThat(ParserService.selectBestQuality(videoLinks)).isNull();
    }

    @Test
    @DisplayName(
            "selectBestQuality picks max numeric even when ADR 0004 is violated (1080+ appears)"
                    + " — counter alerts us, code keeps working")
    void selectBestQualityHandlesFutureHigherQualities() {
        Map<String, String> videoLinks = new HashMap<>();
        videoLinks.put("720", "https://cdn.example/720.mp4");
        videoLinks.put("1080", "https://cdn.example/1080.mp4");
        videoLinks.put("2160", "https://cdn.example/2160.mp4");

        assertThat(ParserService.selectBestQuality(videoLinks))
                .as("If Kodik ever adds 1080+ buckets, our code is ready — picks max numeric.")
                .isEqualTo("https://cdn.example/2160.mp4");
    }

    @Test
    @DisplayName("refreshExpiredLinks: caps repo batch by maintenance.maxBatchPerTick (TD-PR-5)")
    void refreshExpiredLinksHonoursMaxBatchPerTick() {
        OrinunoProperties props = new OrinunoProperties();
        props.getKodik().setRequestDelayMs(0);
        props.getDecoder().setRefreshBatchSize(50);
        props.getDecoder().getMaintenance().setMaxBatchPerTick(3);
        props.getDecoder().getMaintenance().setTickTimeoutSeconds(5);
        ParserService bounded =
                new ParserService(
                        kodikApiClient,
                        contentService,
                        decoderService,
                        episodeVariantRepository,
                        props,
                        kodikCdnHostMetrics,
                        decoderMetrics);

        when(episodeVariantRepository.findExpiredLinks(anyInt(), anyInt())).thenReturn(List.of());

        bounded.refreshExpiredLinks();

        verify(episodeVariantRepository).findExpiredLinks(props.getDecoder().getLinkTtlHours(), 3);
        verifyNoInteractions(decoderService);
    }

    @Test
    @DisplayName("retryFailedDecodes: caps repo batch by maintenance.maxBatchPerTick (TD-PR-5)")
    void retryFailedDecodesHonoursMaxBatchPerTick() {
        OrinunoProperties props = new OrinunoProperties();
        props.getKodik().setRequestDelayMs(0);
        props.getDecoder().setRefreshBatchSize(50);
        props.getDecoder().getMaintenance().setMaxBatchPerTick(2);
        ParserService bounded =
                new ParserService(
                        kodikApiClient,
                        contentService,
                        decoderService,
                        episodeVariantRepository,
                        props,
                        kodikCdnHostMetrics,
                        decoderMetrics);

        when(episodeVariantRepository.findFailedDecode(anyInt())).thenReturn(List.of());

        bounded.retryFailedDecodes();

        verify(episodeVariantRepository).findFailedDecode(2);
    }

    @Test
    @DisplayName(
            "refreshExpiredLinks: returns within wall-clock timeout when decoder hangs (TD-PR-5)")
    void refreshExpiredLinksAbortsOnTickTimeout() {
        OrinunoProperties props = new OrinunoProperties();
        props.getKodik().setRequestDelayMs(0);
        props.getDecoder().setRefreshBatchSize(2);
        props.getDecoder().getMaintenance().setMaxBatchPerTick(2);
        props.getDecoder().getMaintenance().setTickTimeoutSeconds(1);
        ParserService bounded =
                new ParserService(
                        kodikApiClient,
                        contentService,
                        decoderService,
                        episodeVariantRepository,
                        props,
                        kodikCdnHostMetrics,
                        decoderMetrics);

        List<KodikEpisodeVariant> expired =
                IntStream.range(0, 2)
                        .mapToObj(
                                i ->
                                        KodikEpisodeVariant.builder()
                                                .id((long) i)
                                                .kodikLink("//k/" + i)
                                                .build())
                        .toList();
        when(episodeVariantRepository.findExpiredLinks(anyInt(), anyInt())).thenReturn(expired);
        when(decoderService.decode(any())).thenReturn(Mono.never());

        long start = System.currentTimeMillis();
        bounded.refreshExpiredLinks();
        long elapsedMs = System.currentTimeMillis() - start;

        assertThat(elapsedMs).isLessThan(5_000L);
    }
}
