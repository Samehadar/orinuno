package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.orinuno.model.EpisodeSource;
import com.orinuno.model.EpisodeVideo;
import com.orinuno.model.KodikEpisodeVariant;
import com.orinuno.repository.EpisodeSourceRepository;
import com.orinuno.repository.EpisodeVideoRepository;
import com.orinuno.service.decoder.DecodeAttemptResult;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class KodikEpisodeDualWriteServiceTest {

    private EpisodeSourceRepository sourceRepo;
    private EpisodeVideoRepository videoRepo;
    private KodikEpisodeDualWriteService service;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = (ObjectProvider<T>) mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @BeforeEach
    void setUp() {
        sourceRepo = mock(EpisodeSourceRepository.class);
        videoRepo = mock(EpisodeVideoRepository.class);
        service = new KodikEpisodeDualWriteService(providerOf(sourceRepo), providerOf(videoRepo));
        doAnswer(
                        invocation -> {
                            EpisodeSource src = invocation.getArgument(0, EpisodeSource.class);
                            src.setId(42L);
                            return null;
                        })
                .when(sourceRepo)
                .upsert(any());
    }

    private static KodikEpisodeVariant variant() {
        return KodikEpisodeVariant.builder()
                .id(1L)
                .contentId(7L)
                .seasonNumber(1)
                .episodeNumber(5)
                .translationId(123)
                .translationTitle("AniLibria")
                .kodikLink("//kodik.cc/seria/1/abc")
                .build();
    }

    @Test
    @DisplayName("mirror writes one source row + one video row for single-quality result")
    void mirrorSingleQuality() {
        DecodeAttemptResult result = DecodeAttemptResult.regex(Map.of("720", "https://cdn/m.m3u8"));

        service.mirrorDecode(variant(), result, "720", "https://cdn/m.m3u8");

        ArgumentCaptor<EpisodeSource> sCap = ArgumentCaptor.forClass(EpisodeSource.class);
        verify(sourceRepo).upsert(sCap.capture());
        assertThat(sCap.getValue().getProvider()).isEqualTo("KODIK");
        assertThat(sCap.getValue().getContentId()).isEqualTo(7L);
        assertThat(sCap.getValue().getSeason()).isEqualTo(1);
        assertThat(sCap.getValue().getEpisode()).isEqualTo(5);
        assertThat(sCap.getValue().getTranslatorId()).isEqualTo("123");
        assertThat(sCap.getValue().getTranslatorName()).isEqualTo("AniLibria");
        assertThat(sCap.getValue().getSourceUrl()).isEqualTo("//kodik.cc/seria/1/abc");

        ArgumentCaptor<EpisodeVideo> vCap = ArgumentCaptor.forClass(EpisodeVideo.class);
        verify(videoRepo, times(1)).upsertDecoded(vCap.capture());
        EpisodeVideo video = vCap.getValue();
        assertThat(video.getSourceId()).isEqualTo(42L);
        assertThat(video.getQuality()).isEqualTo("720");
        assertThat(video.getVideoUrl()).isEqualTo("https://cdn/m.m3u8");
        assertThat(video.getVideoFormat()).isEqualTo("application/x-mpegURL");
        assertThat(video.getDecodeMethod()).isEqualTo("REGEX");
    }

    @Test
    @DisplayName("mirror writes one row per extra quality bucket beyond the picked one")
    void mirrorMultipleQualities() {
        DecodeAttemptResult result =
                DecodeAttemptResult.regex(
                        Map.of(
                                "360", "https://cdn/360.m3u8",
                                "480", "https://cdn/480.m3u8",
                                "720", "https://cdn/720.m3u8"));

        service.mirrorDecode(variant(), result, "720", "https://cdn/720.m3u8");

        verify(videoRepo, times(3)).upsertDecoded(any());
    }

    @Test
    @DisplayName("mirror is a no-op when repositories are not wired (ObjectProvider returns null)")
    void mirrorNoOpWithoutRepos() {
        KodikEpisodeDualWriteService nopService =
                new KodikEpisodeDualWriteService(providerOf(null), providerOf(null));
        nopService.mirrorDecode(
                variant(), DecodeAttemptResult.regex(Map.of("720", "u")), "720", "u");
        verify(sourceRepo, never()).upsert(any());
        verify(videoRepo, never()).upsertDecoded(any());
    }

    @Test
    @DisplayName("mirror absorbs source-repo failures without throwing")
    void mirrorAbsorbsSourceFailures() {
        doThrow(new RuntimeException("db down")).when(sourceRepo).upsert(any());
        service.mirrorDecode(variant(), DecodeAttemptResult.regex(Map.of("720", "u")), "720", "u");
        verify(videoRepo, never()).upsertDecoded(any());
    }

    @Test
    @DisplayName("mirror skips when picked URL is null")
    void mirrorSkipsWithoutPickedUrl() {
        service.mirrorDecode(variant(), DecodeAttemptResult.regex(Map.of()), "720", null);
        verify(sourceRepo, never()).upsert(any());
    }

    @Test
    @DisplayName("inferFormat detects HLS / DASH / MP4 / unknown")
    void inferFormatDispatch() {
        assertThat(KodikEpisodeDualWriteService.inferFormat("https://x/y.m3u8?t=1"))
                .isEqualTo("application/x-mpegURL");
        assertThat(KodikEpisodeDualWriteService.inferFormat("https://x/y.MPD"))
                .isEqualTo("application/dash+xml");
        assertThat(KodikEpisodeDualWriteService.inferFormat("https://x/y.mp4"))
                .isEqualTo("video/mp4");
        assertThat(KodikEpisodeDualWriteService.inferFormat("https://x/no-suffix")).isNull();
        assertThat(KodikEpisodeDualWriteService.inferFormat(null)).isNull();
    }

    @Test
    @DisplayName("buildSource sets discoveredAt + lastSeenAt to the same instant")
    void buildSourceTimestamps() {
        LocalDateTime now = LocalDateTime.now();
        EpisodeSource s = KodikEpisodeDualWriteService.buildSource(variant(), now);
        assertThat(s.getDiscoveredAt()).isEqualTo(now);
        assertThat(s.getLastSeenAt()).isEqualTo(now);
    }
}
