package com.orinuno.cvh.downloader.candidate;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.model.CvhVideoSources;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateChainTest {

    private static CvhVideoSources fullLadder() {
        return new CvhVideoSources(
                1L,
                100,
                "https://thumb",
                "https://hls/m.m3u8",
                "https://dash/m.mpd",
                "https://mp4/1080.mp4",
                "https://mp4/720.mp4",
                "https://mp4/480.mp4",
                "https://mp4/360.mp4",
                "https://mp4/240.mp4",
                "https://mp4/144.mp4",
                Instant.now().plusSeconds(3600));
    }

    @Test
    void bestFirstOrdersMp4LadderThenHlsThenDash() {
        List<DownloadCandidate> chain =
                CandidateChain.from(fullLadder(), QualityPreference.BEST_FIRST);
        assertThat(chain).hasSize(8);
        assertThat(chain.get(0).quality()).isEqualTo(Mp4Quality.P1080);
        assertThat(chain.get(5).quality()).isEqualTo(Mp4Quality.P144);
        assertThat(chain.get(6).format()).isEqualTo(DownloadFormat.HLS);
        assertThat(chain.get(7).format()).isEqualTo(DownloadFormat.DASH);
    }

    @Test
    void smallestFirstReversesMp4Ladder() {
        List<DownloadCandidate> chain =
                CandidateChain.from(fullLadder(), QualityPreference.SMALLEST_FIRST);
        assertThat(chain.get(0).quality()).isEqualTo(Mp4Quality.P144);
        assertThat(chain.get(5).quality()).isEqualTo(Mp4Quality.P1080);
        assertThat(chain.get(6).format()).isEqualTo(DownloadFormat.HLS);
    }

    @Test
    void hlsFirstPutsHlsAndDashBeforeMp4() {
        List<DownloadCandidate> chain =
                CandidateChain.from(fullLadder(), QualityPreference.HLS_FIRST);
        assertThat(chain.get(0).format()).isEqualTo(DownloadFormat.HLS);
        assertThat(chain.get(1).format()).isEqualTo(DownloadFormat.DASH);
        assertThat(chain.get(2).quality()).isEqualTo(Mp4Quality.P1080);
    }

    @Test
    void skipsBlankUrls() {
        CvhVideoSources sparse =
                new CvhVideoSources(
                        1L,
                        100,
                        "t",
                        "https://hls/m.m3u8",
                        "",
                        "",
                        "https://720.mp4",
                        null,
                        null,
                        null,
                        null,
                        Instant.now().plusSeconds(3600));
        List<DownloadCandidate> chain = CandidateChain.from(sparse, QualityPreference.BEST_FIRST);
        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).quality()).isEqualTo(Mp4Quality.P720);
        assertThat(chain.get(1).format()).isEqualTo(DownloadFormat.HLS);
    }

    @Test
    void nullSourcesYieldsEmpty() {
        assertThat(CandidateChain.from(null, QualityPreference.BEST_FIRST)).isEmpty();
    }

    @Test
    void nullPreferenceFallsBackToBestFirst() {
        List<DownloadCandidate> chain = CandidateChain.from(fullLadder(), null);
        assertThat(chain.get(0).quality()).isEqualTo(Mp4Quality.P1080);
    }
}
