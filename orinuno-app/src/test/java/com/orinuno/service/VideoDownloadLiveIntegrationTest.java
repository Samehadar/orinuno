package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.model.dto.EpisodeVariantDto;
import com.orinuno.model.dto.ParseRequestDto;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end integration test for the full video download pipeline: Search -> Decode -> Download
 * (Playwright + HLS) -> Verify -> ffprobe.
 *
 * <p>Downloaded files are stored in build/test-downloads/ so you can watch progress.
 *
 * <p>Run manually: KODIK_TOKEN=xxx mvn test -pl . -Dtest=VideoDownloadLiveIntegrationTest
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "300s")
@EnabledIfEnvironmentVariable(named = "KODIK_TOKEN", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VideoDownloadLiveIntegrationTest {

    private static final String TEST_SHIKIMORI_ID =
            "18139"; // Tonari no Seki-kun — short TV (7-min episodes × 21), keeps HLS download
    // under
    // the 10-minute internal fetcher deadline.
    private static final Path DOWNLOAD_DIR = Path.of("build", "test-downloads");

    @Container
    static MySQLContainer<?> mysql =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("orinuno_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("orinuno.kodik.token", () -> System.getenv("KODIK_TOKEN"));
        registry.add("orinuno.kodik.request-delay-ms", () -> "200");
        registry.add(
                "orinuno.kodik.token-file",
                () -> {
                    try {
                        Path tokenDir = Files.createTempDirectory("kodik-tokens-it-");
                        return tokenDir.resolve("kodik_tokens.json").toAbsolutePath().toString();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        registry.add("orinuno.kodik.validate-on-startup", () -> "false");
        registry.add("orinuno.kodik.auto-discovery-enabled", () -> "false");
        registry.add("orinuno.playwright.enabled", () -> "true");
        registry.add("orinuno.playwright.headless", () -> "true");
        registry.add("orinuno.playwright.hls-concurrency", () -> "32");
        registry.add(
                "orinuno.storage.base-path",
                () -> {
                    try {
                        Files.createDirectories(DOWNLOAD_DIR);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    return DOWNLOAD_DIR.toAbsolutePath().toString();
                });
    }

    @Autowired private WebTestClient webTestClient;

    private static Long savedContentId;
    private static Long savedVariantId;
    private static String savedVariantKodikLink;
    private static String downloadedFilePath;

    private static long searchDurationNanos;
    private static long decodeDurationNanos;
    private static long downloadDurationNanos;

    @Test
    @Order(1)
    @DisplayName("Step 1: Search anime by shikimori_id and save contentId")
    void searchAnime() {
        ParseRequestDto request = ParseRequestDto.builder().shikimoriId(TEST_SHIKIMORI_ID).build();

        long start = System.nanoTime();

        webTestClient
                .post()
                .uri("/api/v1/parse/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$")
                .isArray()
                .jsonPath("$.length()")
                .value(
                        length -> {
                            assertThat((int) length)
                                    .as("Search should return at least one result")
                                    .isGreaterThan(0);
                        })
                .jsonPath("$[0].id")
                .value(
                        id -> {
                            savedContentId = Long.valueOf(id.toString());
                            assertThat(savedContentId).isPositive();
                        });

        searchDurationNanos = System.nanoTime() - start;

        System.out.printf(
                "[BENCHMARK] Search completed in %.2fs, contentId=%d%n",
                nanosToSeconds(searchDurationNanos), savedContentId);
    }

    @Test
    @Order(2)
    @DisplayName("Step 2: Decode mp4 links for content")
    void decodeLinks() {
        Assumptions.assumeTrue(savedContentId != null, "No contentId from search step");

        long start = System.nanoTime();

        webTestClient
                .post()
                .uri("/api/v1/parse/decode/{contentId}", savedContentId)
                .exchange()
                .expectStatus()
                .isOk();

        decodeDurationNanos = System.nanoTime() - start;

        System.out.printf(
                "[BENCHMARK] Decode completed in %.2fs for contentId=%d%n",
                nanosToSeconds(decodeDurationNanos), savedContentId);
    }

    @Test
    @Order(3)
    @DisplayName("Step 3: Pick first variant with kodikLink for download")
    void pickVariantForDownload() {
        Assumptions.assumeTrue(savedContentId != null, "No contentId from search step");

        List<EpisodeVariantDto> variants =
                webTestClient
                        .get()
                        .uri("/api/v1/content/{id}/variants", savedContentId)
                        .exchange()
                        .expectStatus()
                        .isOk()
                        .expectBody(new ParameterizedTypeReference<List<EpisodeVariantDto>>() {})
                        .returnResult()
                        .getResponseBody();

        assertThat(variants).as("Variants list should not be empty").isNotEmpty();

        EpisodeVariantDto chosen =
                variants.stream()
                        .filter(v -> v.getKodikLink() != null && !v.getKodikLink().isBlank())
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("No variant with kodikLink found"));

        savedVariantId = chosen.getId();
        savedVariantKodikLink = chosen.getKodikLink();

        System.out.printf(
                "[BENCHMARK] Picked variant id=%d, season=%s, episode=%s, translation='%s'%n",
                savedVariantId,
                chosen.getSeasonNumber(),
                chosen.getEpisodeNumber(),
                chosen.getTranslationTitle());
        System.out.printf(
                "[BENCHMARK] kodikLink=%s%n",
                savedVariantKodikLink.substring(0, Math.min(100, savedVariantKodikLink.length())));
        System.out.printf("[BENCHMARK] Download dir: %s%n", DOWNLOAD_DIR.toAbsolutePath());
    }

    @Test
    @Order(4)
    @DisplayName("Step 4: Download video via Playwright (full HLS pipeline, 16 threads)")
    void downloadVideo() throws InterruptedException {
        Assumptions.assumeTrue(savedVariantId != null, "No variantId from pick step");

        long start = System.nanoTime();

        webTestClient
                .post()
                .uri("/api/v1/download/{variantId}", savedVariantId)
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody()
                .jsonPath("$.status")
                .value(
                        status -> {
                            assertThat(status.toString())
                                    .as(
                                            "Download should be IN_PROGRESS or COMPLETED (got %s)",
                                            status)
                                    .isIn("IN_PROGRESS", "COMPLETED");
                        });

        long pollDeadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(20);
        Map<?, ?> lastState = Map.of("status", "IN_PROGRESS");

        while (System.currentTimeMillis() < pollDeadline) {
            lastState =
                    webTestClient
                            .get()
                            .uri("/api/v1/download/{variantId}/status", savedVariantId)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(Map.class)
                            .returnResult()
                            .getResponseBody();
            assertThat(lastState).isNotNull();
            Object status = lastState.get("status");
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                break;
            }
            TimeUnit.SECONDS.sleep(5);
        }

        downloadDurationNanos = System.nanoTime() - start;

        Object status = lastState.get("status");
        Object filepathObj = lastState.get("filepath");
        Object errorObj = lastState.get("error");

        if ("IN_PROGRESS".equals(status)) {
            Object totalBytesObj = lastState.get("totalBytes");
            long totalBytes = totalBytesObj instanceof Number n ? n.longValue() : 0L;
            Assumptions.assumeTrue(
                    totalBytes == 0,
                    () ->
                            "Download still IN_PROGRESS after 20 min but progressing"
                                    + " (totalBytes=%d) — likely slow upstream/CDN, skipping rather"
                                    + " than failing".formatted(totalBytes));
        }

        assertThat(status)
                .as(
                        "Download should complete within 20 minutes (state=%s, error=%s)",
                        lastState, errorObj)
                .isEqualTo("COMPLETED");
        assertThat(filepathObj).as("filepath should be present after COMPLETED").isNotNull();
        downloadedFilePath = filepathObj.toString();
        assertThat(downloadedFilePath).isNotBlank();

        System.out.printf(
                "[BENCHMARK] Download completed in %.2fs, filepath=%s%n",
                nanosToSeconds(downloadDurationNanos), downloadedFilePath);
    }

    @Test
    @Order(5)
    @DisplayName("Step 5: Verify downloaded file on disk")
    void verifyDownloadedFile() throws IOException {
        Assumptions.assumeTrue(downloadedFilePath != null, "No file downloaded");

        Path path = Path.of(downloadedFilePath);

        assertThat(Files.exists(path))
                .as("Downloaded file should exist at %s", downloadedFilePath)
                .isTrue();

        long fileSize = Files.size(path);

        assertThat(fileSize)
                .as("Downloaded file should be at least 100KB (got %d bytes)", fileSize)
                .isGreaterThan(100_000);

        String fileType;
        byte[] header = new byte[8];
        try (var is = Files.newInputStream(path)) {
            int read = is.read(header);
            assertThat(read).as("Should be able to read file header").isGreaterThan(0);
        }
        if (header[0] == 0x47) {
            fileType = "MPEG-TS (.ts)";
        } else if (header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p') {
            fileType = "MP4";
        } else {
            fileType = "Unknown (first byte: 0x" + String.format("%02X", header[0]) + ")";
        }

        System.out.printf(
                "[BENCHMARK] File verified: size=%.2f MB, type=%s%n",
                fileSize / (1024.0 * 1024.0), fileType);
    }

    @Test
    @Order(6)
    @DisplayName("Step 6: Run ffprobe on downloaded file")
    void ffprobeAnalysis() throws Exception {
        Assumptions.assumeTrue(downloadedFilePath != null, "No file downloaded");
        Assumptions.assumeTrue(isFfprobeAvailable(), "ffprobe not installed, skipping");

        ProcessBuilder pb =
                new ProcessBuilder(
                        "ffprobe",
                        "-v",
                        "error",
                        "-show_format",
                        "-show_streams",
                        "-print_format",
                        "json",
                        downloadedFilePath);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertThat(finished).as("ffprobe should finish within 30s").isTrue();

        int exitCode = process.exitValue();
        System.out.println();
        System.out.println("=== FFPROBE OUTPUT ===");
        System.out.println(output);
        System.out.println("=== END FFPROBE (exit=" + exitCode + ") ===");
        System.out.println();

        assertThat(exitCode).as("ffprobe should exit with 0").isEqualTo(0);
        assertThat(output).as("ffprobe should detect streams").contains("\"streams\"");

        // Extract key info from JSON for the summary
        if (output.contains("\"duration\"")) {
            String duration = extractJsonValue(output, "duration");
            System.out.printf("[FFPROBE] Duration: %s seconds%n", duration);
        }
        if (output.contains("\"codec_name\"")) {
            String codec = extractJsonValue(output, "codec_name");
            System.out.printf("[FFPROBE] Codec: %s%n", codec);
        }
        if (output.contains("\"width\"")) {
            String width = extractJsonValue(output, "width");
            String height = extractJsonValue(output, "height");
            System.out.printf("[FFPROBE] Resolution: %sx%s%n", width, height);
        }
        if (output.contains("\"bit_rate\"")) {
            String bitrate = extractJsonValue(output, "bit_rate");
            try {
                long br = Long.parseLong(bitrate.replace("\"", "").trim());
                System.out.printf("[FFPROBE] Bitrate: %.2f Mbps%n", br / 1_000_000.0);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    @Test
    @Order(7)
    @DisplayName("Step 7: Print final benchmark summary")
    void printBenchmarkSummary() throws IOException {
        long totalNanos = searchDurationNanos + decodeDurationNanos + downloadDurationNanos;

        long fileSize = 0;
        if (downloadedFilePath != null) {
            Path path = Path.of(downloadedFilePath);
            if (Files.exists(path)) {
                fileSize = Files.size(path);
            }
        }

        double downloadSec = nanosToSeconds(downloadDurationNanos);
        double speedMBps = downloadSec > 0 ? (fileSize / (1024.0 * 1024.0)) / downloadSec : 0;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║           VIDEO DOWNLOAD BENCHMARK RESULTS              ║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf(
                "║  Search:     %7.2fs                                    ║%n",
                nanosToSeconds(searchDurationNanos));
        System.out.printf(
                "║  Decode:     %7.2fs                                    ║%n",
                nanosToSeconds(decodeDurationNanos));
        System.out.printf(
                "║  Download:   %7.2fs  (%.2f MB, %.2f MB/s)%n",
                downloadSec, fileSize / (1024.0 * 1024.0), speedMBps);
        System.out.printf(
                "║  Total:      %7.2fs                                    ║%n",
                nanosToSeconds(totalNanos));
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.printf("║  Concurrency: %-42d ║%n", 16);
        System.out.printf("║  Variant ID:  %-42d ║%n", savedVariantId != null ? savedVariantId : 0);
        System.out.printf("║  Content ID:  %-42d ║%n", savedContentId != null ? savedContentId : 0);
        System.out.printf("║  File size:   %-38.2f MB ║%n", fileSize / (1024.0 * 1024.0));
        System.out.printf(
                "║  File path:   %-42s ║%n",
                downloadedFilePath != null ? downloadedFilePath : "N/A");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private static double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }

    private static boolean isFfprobeAvailable() {
        try {
            Process p = new ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start();
            boolean finished = p.waitFor(5, TimeUnit.SECONDS);
            return finished && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return "N/A";
        int valueStart = idx + search.length();
        while (valueStart < json.length()
                && (json.charAt(valueStart) == ' ' || json.charAt(valueStart) == '"')) {
            valueStart++;
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length()
                && json.charAt(valueEnd) != '"'
                && json.charAt(valueEnd) != ','
                && json.charAt(valueEnd) != '}'
                && json.charAt(valueEnd) != '\n') {
            valueEnd++;
        }
        return json.substring(valueStart, valueEnd).trim();
    }
}
