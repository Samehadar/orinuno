package com.orinuno.aksor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.orinuno.aksor.model.AksorEpisode;
import com.orinuno.cvh.downloader.CvhDownloadProgress;
import com.orinuno.cvh.downloader.CvhDownloadRequest;
import com.orinuno.cvh.downloader.CvhDownloaderConfig;
import com.orinuno.cvh.downloader.candidate.DownloadCandidate;
import com.orinuno.cvh.downloader.ffmpeg.FfmpegRemuxer;
import com.orinuno.cvh.downloader.fs.LocalFsDestination;
import com.orinuno.cvh.downloader.strategy.DashStrategy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end live test: {@link AksorClient} produces an MPEG-DASH manifest URL, {@link
 * DashStrategy} (from {@code cvh-downloader-sdk}) hands it to ffmpeg, and the resulting MP4 is
 * validated on disk. Closes the loop between the two SDKs without coupling production code.
 *
 * <p>Required env:
 *
 * <ul>
 *   <li>{@code AKSOR_LIVE_TESTS=1|true|yes}
 *   <li>{@code AKSOR_LIVE_TEST_URL} — host page URL on yummyani
 *   <li>system {@code ffmpeg} on {@code PATH}
 * </ul>
 *
 * <p>The full DASH manifest for one episode is ~250 MB at 1080p; live download throughput on the
 * Aksor CDN is roughly 1–2 MB/s, so the test allows a generous {@link #DOWNLOAD_TIMEOUT} and uses
 * an {@code AksorEpisodeFilter} to grab only the first AniLibria episode (one candidate, not the
 * whole series).
 */
@EnabledIfEnvironmentVariable(named = "AKSOR_LIVE_TESTS", matches = "1|true|TRUE|yes")
class AksorCvhDownloaderE2eLiveTest {

    private static final Duration DECODE_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(20);

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env " + key + " is required for live tests");
        }
        return v;
    }

    @Test
    void aksorMpdUrlDownloadsThroughDashStrategy(@TempDir Path baseDir) throws IOException {
        Optional<String> ffmpegBinary = resolveDashCapableFfmpeg();
        assumeTrue(
                ffmpegBinary.isPresent(),
                "No ffmpeg binary on the system exposes a DASH demuxer (needs libxml2 in the"
                        + " build). Install a full ffmpeg (e.g. brew install ffmpeg) or set"
                        + " AKSOR_E2E_FFMPEG_BIN to an absolute path.");

        // 1. Decode via aksor-sdk, narrow to one episode to keep the download small.
        AksorClient client = AksorClient.builder().build();
        AksorDecodeResult decoded =
                client.decode(
                                requiredEnv("AKSOR_LIVE_TEST_URL"),
                                AksorEpisodeFilter.byNumber("1").andDubbing("AniLibria"))
                        .block(DECODE_TIMEOUT);
        assertThat(decoded).isNotNull();
        assertThat(decoded.success())
                .as("aksor decode success, errorCode=%s", decoded.errorCode())
                .isTrue();
        assertThat(decoded.value().episodes()).hasSize(1);
        AksorEpisode episode = decoded.value().episodes().get(0);
        String mpdUrl = episode.qualities().bestAvailable();
        assertThat(mpdUrl).startsWith("https://").endsWith(".mpd");

        // 2. Hand the MPD URL to cvh-downloader-sdk's DashStrategy. Pin the ffmpeg binary
        // explicitly so PATH ordering on the dev machine (where a libxml2-less build can shadow
        // brew's) does not silently pick the broken one.
        CvhDownloaderConfig config =
                CvhDownloaderConfig.builder()
                        .outputBaseDir(baseDir)
                        .ffmpegBinary(ffmpegBinary.get())
                        // Bump ffmpeg timeout — DASH copy of a 22-min episode can run several
                        // minutes on a slow line.
                        .ffmpegTimeoutSeconds(900)
                        .build();
        LocalFsDestination fs = new LocalFsDestination(baseDir);
        DashStrategy strategy = new DashStrategy(config, new FfmpegRemuxer());

        DownloadCandidate candidate = DownloadCandidate.dash(mpdUrl);
        CvhDownloadRequest request =
                CvhDownloadRequest.builder()
                        .filenameHint("aksor-e2e")
                        .referer("https://old.yummyani.me/")
                        .build();
        Path target = fs.resolveSafe("aksor-e2e.mp4");
        CvhDownloadProgress progress = new CvhDownloadProgress();

        Path written =
                strategy.download(candidate, request, target, progress).block(DOWNLOAD_TIMEOUT);

        // 3. Validate on-disk MP4.
        assertThat(written).isNotNull();
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.size(written))
                .as("downloaded file must be non-trivial")
                .isGreaterThan(1_000_000L);
        assertMp4Magic(written);
    }

    /**
     * Picks the first ffmpeg binary that exposes a DASH demuxer (needs libxml2 in the build).
     * Probes in order: {@code AKSOR_E2E_FFMPEG_BIN} env override, common brew / apt locations, bare
     * {@code "ffmpeg"} on {@code PATH}. PATH lookup goes last so a {@code .local/bin} libxml2-less
     * build cannot shadow a properly built brew/apt binary further down the list.
     */
    private static Optional<String> resolveDashCapableFfmpeg() {
        List<String> candidates =
                new java.util.ArrayList<>(
                        List.of(
                                "/opt/homebrew/bin/ffmpeg",
                                "/usr/local/bin/ffmpeg",
                                "/usr/bin/ffmpeg",
                                "ffmpeg"));
        String envOverride = System.getenv("AKSOR_E2E_FFMPEG_BIN");
        if (envOverride != null && !envOverride.isBlank()) {
            candidates.add(0, envOverride);
        }
        for (String c : candidates) {
            if (binaryHasDashDemuxer(c)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** Inspects {@code <binary> -demuxers} for a {@code D dash} row. */
    private static boolean binaryHasDashDemuxer(String binary) {
        try {
            Process p =
                    new ProcessBuilder(binary, "-hide_banner", "-demuxers")
                            .redirectErrorStream(true)
                            .start();
            Pattern dashRow = Pattern.compile("^\\s*D\\s+dash\\s", Pattern.MULTILINE);
            try (BufferedReader r =
                    new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (dashRow.matcher(line).find()) {
                        p.destroy();
                        return true;
                    }
                }
            }
            p.waitFor();
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    private static void assertMp4Magic(Path mp4) throws IOException {
        byte[] head = new byte[12];
        try (var in = Files.newInputStream(mp4)) {
            int n = in.read(head);
            assertThat(n).isGreaterThanOrEqualTo(8);
        }
        // MP4 / fragmented MP4: bytes 4..7 spell "ftyp".
        String fourcc = new String(head, 4, 4, StandardCharsets.US_ASCII);
        assertThat(fourcc).as("MP4 magic 'ftyp' at offset 4").isEqualTo("ftyp");
    }
}
