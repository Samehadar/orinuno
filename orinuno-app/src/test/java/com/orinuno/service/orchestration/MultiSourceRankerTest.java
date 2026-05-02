package com.orinuno.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.model.EpisodeSource;
import com.orinuno.model.EpisodeVideo;
import com.orinuno.service.orchestration.MultiSourceRanker.RankedCandidate;
import com.orinuno.service.orchestration.MultiSourceRanker.RankingPreferences;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultiSourceRankerTest {

    private final MultiSourceRanker ranker = new MultiSourceRanker();

    private static EpisodeSource src(long id, String provider) {
        EpisodeSource s = new EpisodeSource();
        s.setId(id);
        s.setProvider(provider);
        s.setContentId(1L);
        s.setSeason(1);
        s.setEpisode(1);
        return s;
    }

    private static EpisodeVideo vid(
            long sourceId, String quality, LocalDateTime decodedAt, int failed) {
        return EpisodeVideo.builder()
                .sourceId(sourceId)
                .quality(quality)
                .videoUrl("https://x/" + sourceId + "/" + quality)
                .decodedAt(decodedAt)
                .decodeFailedCount(failed)
                .build();
    }

    @Test
    void rankerHonoursDefaultProviderOrder() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 0);
        List<EpisodeSource> sources =
                List.of(
                        src(1L, EpisodeSource.Provider.SIBNET),
                        src(2L, EpisodeSource.Provider.KODIK),
                        src(3L, EpisodeSource.Provider.ANIBOOM));
        List<EpisodeVideo> videos =
                List.of(vid(1L, "720", now, 0), vid(2L, "720", now, 0), vid(3L, "720", now, 0));
        RankingPreferences prefs = RankingPreferences.defaults();
        prefs.now = now;

        List<RankedCandidate> ranked = ranker.rank(sources, videos, prefs);
        assertThat(ranked).hasSize(3);
        assertThat(ranked.get(0).source().getProvider()).isEqualTo("KODIK");
        assertThat(ranked.get(1).source().getProvider()).isEqualTo("ANIBOOM");
        assertThat(ranked.get(2).source().getProvider()).isEqualTo("SIBNET");
    }

    @Test
    void rankerDemotesFailingCandidates() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 0);
        List<EpisodeSource> sources = List.of(src(1L, "KODIK"), src(2L, "KODIK"));
        List<EpisodeVideo> videos = List.of(vid(1L, "720", now, 5), vid(2L, "720", now, 0));
        List<RankedCandidate> ranked =
                ranker.rank(sources, videos, withClock(RankingPreferences.defaults(), now));
        assertThat(ranked.get(0).video().getDecodeFailedCount()).isZero();
        assertThat(ranked.get(1).video().getDecodeFailedCount()).isEqualTo(5);
    }

    @Test
    void rankerDemotesStaleCandidates() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 0);
        List<EpisodeSource> sources = List.of(src(1L, "KODIK"), src(2L, "KODIK"));
        List<EpisodeVideo> videos =
                List.of(vid(1L, "720", now.minusHours(7), 0), vid(2L, "720", now, 0));
        List<RankedCandidate> ranked =
                ranker.rank(sources, videos, withClock(RankingPreferences.defaults(), now));
        assertThat(ranked.get(0).video().getDecodedAt()).isEqualTo(now);
    }

    @Test
    void rankerHonoursPreferOverride() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 0);
        List<EpisodeSource> sources =
                List.of(
                        src(1L, EpisodeSource.Provider.KODIK),
                        src(2L, EpisodeSource.Provider.SIBNET));
        List<EpisodeVideo> videos = List.of(vid(1L, "720", now, 0), vid(2L, "720", now, 0));
        RankingPreferences prefs = RankingPreferences.defaults();
        prefs.providerOrder = List.of("SIBNET", "KODIK");
        prefs.now = now;
        List<RankedCandidate> ranked = ranker.rank(sources, videos, prefs);
        assertThat(ranked.get(0).source().getProvider()).isEqualTo("SIBNET");
    }

    @Test
    void rankerHandlesEmptyInputs() {
        assertThat(ranker.rank(List.of(), List.of(), null)).isEmpty();
        assertThat(ranker.rank(null, null, null)).isEmpty();
    }

    @Test
    void rankerSkipsVideosWithoutSource() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 0);
        List<EpisodeSource> sources = List.of(src(1L, "KODIK"));
        List<EpisodeVideo> videos = List.of(vid(99L, "720", now, 0));
        assertThat(ranker.rank(sources, videos, null)).isEmpty();
    }

    @Test
    void qualityScoreShapes() {
        assertThat(MultiSourceRanker.qualityScore("1080")).isEqualTo(1.0);
        assertThat(MultiSourceRanker.qualityScore("720")).isCloseTo(0.667, within(0.01));
        assertThat(MultiSourceRanker.qualityScore("auto")).isEqualTo(0.4);
        assertThat(MultiSourceRanker.qualityScore("dash")).isEqualTo(0.3);
        assertThat(MultiSourceRanker.qualityScore("garbage")).isZero();
        assertThat(MultiSourceRanker.qualityScore(null)).isZero();
    }

    @Test
    void freshnessScoreShapes() {
        LocalDateTime now = LocalDateTime.of(2026, 5, 2, 12, 0);
        assertThat(MultiSourceRanker.freshnessScore(now, now)).isEqualTo(1.0);
        assertThat(MultiSourceRanker.freshnessScore(now.minusHours(3), now))
                .isCloseTo(0.5, within(0.01));
        assertThat(MultiSourceRanker.freshnessScore(now.minusHours(10), now)).isZero();
        assertThat(MultiSourceRanker.freshnessScore(null, now)).isZero();
    }

    @Test
    void reliabilityScoreShapes() {
        assertThat(MultiSourceRanker.reliabilityScore(0)).isEqualTo(1.0);
        assertThat(MultiSourceRanker.reliabilityScore(null)).isEqualTo(1.0);
        assertThat(MultiSourceRanker.reliabilityScore(2)).isCloseTo(0.6, within(0.01));
        assertThat(MultiSourceRanker.reliabilityScore(99)).isZero();
    }

    private static org.assertj.core.data.Offset<Double> within(double v) {
        return org.assertj.core.data.Offset.offset(v);
    }

    private static RankingPreferences withClock(RankingPreferences prefs, LocalDateTime now) {
        prefs.now = now;
        return prefs;
    }
}
