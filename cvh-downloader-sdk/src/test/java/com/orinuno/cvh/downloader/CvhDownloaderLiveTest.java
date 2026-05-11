package com.orinuno.cvh.downloader;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.CvhClient;
import com.orinuno.cvh.CvhDecodeResult;
import com.orinuno.cvh.downloader.candidate.DownloadFormat;
import com.orinuno.cvh.downloader.candidate.QualityPreference;
import com.orinuno.cvh.model.CvhVideoSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end live test: cvh-sdk fetches a real CvhVideoSources from jut-su.works → cvh-downloader
 * pipes the first MP4 candidate to disk → checks MP4 magic bytes ({@code ftyp}) and minimum size.
 *
 * <p>Required env:
 *
 * <ul>
 *   <li>{@code CVH_LIVE_TESTS=1|true|yes}
 *   <li>{@code CVH_LIVE_TEST_URL} — a jut-su.works URL that embeds CVH (e.g. {@code
 *       https://jut-su.works/all-you-need-is-kill}).
 * </ul>
 *
 * <p>Optional env:
 *
 * <ul>
 *   <li>{@code CVH_LIVE_TEST_PREFERENCE} — {@code BEST_FIRST}|{@code SMALLEST_FIRST}|{@code
 *       HLS_FIRST} (default SMALLEST_FIRST — keeps download size small for CI).
 * </ul>
 *
 * <p>Hits production CVH endpoints and writes a real MP4 to a JUnit temp directory.
 */
@EnabledIfEnvironmentVariable(named = "CVH_LIVE_TESTS", matches = "1|true|TRUE|yes")
class CvhDownloaderLiveTest {

    // Real CVH vkuser.net throughput on a typical home line: 144p episode ≈ 95 MB, ~7 min to drain.
    // We allow generous headroom so a slow CI runner doesn't false-fail.
    private static final Duration BLOCKING_TIMEOUT = Duration.ofMinutes(15);

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env " + key + " is required for live download tests");
        }
        return v;
    }

    private static QualityPreference preferenceFromEnv() {
        String v = System.getenv("CVH_LIVE_TEST_PREFERENCE");
        if (v == null || v.isBlank()) {
            return QualityPreference.SMALLEST_FIRST;
        }
        try {
            return QualityPreference.valueOf(v.trim());
        } catch (IllegalArgumentException ex) {
            return QualityPreference.SMALLEST_FIRST;
        }
    }

    @Test
    void downloadsSmallestMp4FromRealJutsuTitle(@TempDir Path baseDir) throws IOException {
        String pageUrl = requiredEnv("CVH_LIVE_TEST_URL");

        CvhClient sdk = CvhClient.builder().build();
        CvhDecodeResult decode = sdk.decode(pageUrl).block(BLOCKING_TIMEOUT);
        assertThat(decode).isNotNull();
        assertThat(decode.success())
                .as("cvh-sdk decode failed: errorCode=%s", decode.errorCode())
                .isTrue();
        assertThat(decode.value().tracks()).isNotEmpty();
        CvhVideoSources sources = decode.value().tracks().get(0).sources();
        assertThat(sources.mp4_144p()).as("smallest MP4 quality must be available").isNotBlank();

        CvhDownloader downloader =
                CvhDownloader.builder()
                        .config(CvhDownloaderConfig.builder().outputBaseDir(baseDir).build())
                        .build();
        CvhDownloadRequest request =
                CvhDownloadRequest.builder()
                        .filenameHint("live-test")
                        .referer("https://jut-su.works/")
                        .preference(preferenceFromEnv())
                        .build();

        CvhDownloadResult result = downloader.download(sources, request).block(BLOCKING_TIMEOUT);

        assertThat(result).isNotNull();
        assertThat(result.formatUsed())
                .as("expected at least one candidate to succeed")
                .isIn(DownloadFormat.MP4_DIRECT, DownloadFormat.HLS, DownloadFormat.DASH);
        assertThat(Files.exists(result.filepath())).isTrue();
        assertThat(result.bytesWritten())
                .as("downloaded file must be non-trivial")
                .isGreaterThan(100_000L);
        assertMp4Magic(result.filepath());
    }

    private static void assertMp4Magic(Path mp4) throws IOException {
        byte[] head = new byte[12];
        try (var in = Files.newInputStream(mp4)) {
            int n = in.read(head);
            assertThat(n).isGreaterThanOrEqualTo(8);
        }
        // MP4: bytes 4..7 spell "ftyp".
        String fourcc = new String(head, 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(fourcc).as("MP4 magic 'ftyp' at offset 4").isEqualTo("ftyp");
    }
}
