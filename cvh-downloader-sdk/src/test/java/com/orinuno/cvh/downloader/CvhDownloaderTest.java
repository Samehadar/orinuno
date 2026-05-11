package com.orinuno.cvh.downloader;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.candidate.DownloadFormat;
import com.orinuno.cvh.downloader.candidate.Mp4Quality;
import com.orinuno.cvh.downloader.candidate.QualityPreference;
import com.orinuno.cvh.downloader.fs.LocalFsDestination;
import com.orinuno.cvh.downloader.strategy.DownloadStrategy;
import com.orinuno.cvh.model.CvhVideoSources;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Facade-level tests focused on the fallback orchestrator. Strategies are stub implementations that
 * fail the first N candidates and succeed on the (N+1)-th.
 */
class CvhDownloaderTest {

    private static CvhVideoSources twoCandidateSources() {
        return new CvhVideoSources(
                1L,
                100,
                "t",
                null,
                null,
                "https://cdn/1080.mp4",
                "https://cdn/720.mp4",
                null,
                null,
                null,
                null,
                Instant.now().plusSeconds(3600));
    }

    private static class StubStrategy implements DownloadStrategy {
        final DownloadFormat onlyFormat;
        final boolean shouldFail;
        final AtomicInteger calls = new AtomicInteger();

        StubStrategy(DownloadFormat onlyFormat, boolean shouldFail) {
            this.onlyFormat = onlyFormat;
            this.shouldFail = shouldFail;
        }

        @Override
        public boolean supports(DownloadCandidate candidate) {
            return candidate.format() == onlyFormat;
        }

        @Override
        public Mono<Path> download(
                DownloadCandidate candidate,
                CvhDownloadRequest request,
                Path targetMp4Path,
                CvhDownloadProgress progress) {
            calls.incrementAndGet();
            if (shouldFail) {
                return Mono.error(new IOException("simulated failure"));
            }
            try {
                Files.createDirectories(targetMp4Path.getParent());
                Files.writeString(targetMp4Path, "mp4-bytes");
                return Mono.just(targetMp4Path);
            } catch (IOException e) {
                return Mono.error(e);
            }
        }
    }

    private static CvhDownloader buildWith(LocalFsDestination fs, List<DownloadStrategy> strategies)
            throws Exception {
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder().outputBaseDir(fs.baseDir()).build();
        Constructor<CvhDownloader> ctor =
                CvhDownloader.class.getDeclaredConstructor(
                        CvhDownloaderConfig.class, LocalFsDestination.class, List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(config, fs, strategies);
    }

    @Test
    void firstSuccessfulCandidateWins(@TempDir Path baseDir) throws Exception {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        StubStrategy mp4 = new StubStrategy(DownloadFormat.MP4_DIRECT, false);
        CvhDownloader downloader = buildWith(fs, List.of(mp4));

        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("title").referer("https://r/").build();

        StepVerifier.create(downloader.download(twoCandidateSources(), request))
                .assertNext(
                        r -> {
                            assertThat(r.formatUsed()).isEqualTo(DownloadFormat.MP4_DIRECT);
                            assertThat(r.attemptedCandidates()).hasSize(1);
                            assertThat(r.attemptedCandidates().get(0).quality())
                                    .isEqualTo(Mp4Quality.P1080);
                        })
                .verifyComplete();
        assertThat(mp4.calls.get()).isEqualTo(1);
    }

    @Test
    void fallsBackToNextCandidateWhenFirstFails(@TempDir Path baseDir) throws Exception {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        // Strategy fails the first attempt then succeeds on the second.
        StubStrategy strategy =
                new StubStrategy(DownloadFormat.MP4_DIRECT, true) {
                    @Override
                    public Mono<Path> download(
                            DownloadCandidate candidate,
                            CvhDownloadRequest request,
                            Path targetMp4Path,
                            CvhDownloadProgress progress) {
                        if (calls.incrementAndGet() == 1) {
                            return Mono.error(new IOException("first attempt 404"));
                        }
                        try {
                            Files.createDirectories(targetMp4Path.getParent());
                            Files.writeString(targetMp4Path, "ok");
                            return Mono.just(targetMp4Path);
                        } catch (IOException e) {
                            return Mono.error(e);
                        }
                    }
                };
        CvhDownloader downloader = buildWith(fs, List.of(strategy));

        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("fallback").referer("https://r/").build();

        StepVerifier.create(downloader.download(twoCandidateSources(), request))
                .assertNext(
                        r -> {
                            assertThat(r.formatUsed()).isEqualTo(DownloadFormat.MP4_DIRECT);
                            assertThat(r.attemptedCandidates()).hasSize(2);
                            // First attempted 1080p, fallback 720p succeeded.
                            assertThat(r.attemptedCandidates().get(0).quality())
                                    .isEqualTo(Mp4Quality.P1080);
                            assertThat(r.attemptedCandidates().get(1).quality())
                                    .isEqualTo(Mp4Quality.P720);
                        })
                .verifyComplete();
    }

    @Test
    void allFailedYieldsCvhDlAllFailed(@TempDir Path baseDir) throws Exception {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        StubStrategy failing = new StubStrategy(DownloadFormat.MP4_DIRECT, true);
        CvhDownloader downloader = buildWith(fs, List.of(failing));

        CvhDownloadRequest request =
                CvhDownloadRequest.builder().filenameHint("doomed").referer("https://r/").build();

        StepVerifier.create(downloader.download(twoCandidateSources(), request))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((CvhDownloaderException) ex).errorCode())
                                        .isEqualTo(CvhDownloaderErrorCodes.CVH_DL_ALL_FAILED))
                .verify();
    }

    @Test
    void noCandidatesYieldsCvhDlNoCandidates(@TempDir Path baseDir) throws Exception {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        StubStrategy mp4 = new StubStrategy(DownloadFormat.MP4_DIRECT, false);
        CvhDownloader downloader = buildWith(fs, List.of(mp4));

        CvhVideoSources empty =
                new CvhVideoSources(
                        1L, 100, "t", null, null, null, null, null, null, null, null, null);

        StepVerifier.create(
                        downloader.download(
                                empty,
                                CvhDownloadRequest.builder()
                                        .filenameHint("x")
                                        .referer("https://r/")
                                        .build()))
                .expectErrorSatisfies(
                        ex ->
                                assertThat(((CvhDownloaderException) ex).errorCode())
                                        .isEqualTo(CvhDownloaderErrorCodes.CVH_DL_NO_CANDIDATES))
                .verify();
    }

    @Test
    void customChainOverridesPreference(@TempDir Path baseDir) throws Exception {
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        StubStrategy hls = new StubStrategy(DownloadFormat.HLS, false);
        CvhDownloader downloader = buildWith(fs, List.of(hls));

        CvhDownloadRequest request =
                CvhDownloadRequest.builder()
                        .filenameHint("custom")
                        .referer("https://r/")
                        .preference(QualityPreference.BEST_FIRST)
                        .customChain(List.of(DownloadCandidate.hls("https://hls/m.m3u8")))
                        .build();

        StepVerifier.create(downloader.download(twoCandidateSources(), request))
                .assertNext(
                        r -> {
                            assertThat(r.formatUsed()).isEqualTo(DownloadFormat.HLS);
                            assertThat(r.attemptedCandidates()).hasSize(1);
                        })
                .verifyComplete();
    }
}
