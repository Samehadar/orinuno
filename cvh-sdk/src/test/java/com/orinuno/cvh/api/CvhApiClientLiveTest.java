package com.orinuno.cvh.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.model.CvhVideoSources;
import com.orinuno.cvh.model.CvhVoiceTrack;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Live tests for {@link CvhApiClient} against plapi.cdnvideohub.com. Skipped unless {@code
 * CVH_LIVE_TESTS=1}.
 *
 * <p>Required env when enabled:
 *
 * <ul>
 *   <li>{@code CVH_LIVE_TEST_TITLE_ID} — numeric titleId of a known title (e.g. 61192).
 *   <li>{@code CVH_LIVE_TEST_PUBLISHER_ID} — numeric publisherId (e.g. 910).
 * </ul>
 *
 * <p>Optional env: {@code CVH_LIVE_TEST_AGGREGATOR} (defaults to {@code mali}), {@code
 * CVH_LIVE_TEST_REFERER} (defaults to {@code https://jut-su.works/} — must match publisher
 * whitelist).
 */
@EnabledIfEnvironmentVariable(named = "CVH_LIVE_TESTS", matches = "1|true|TRUE|yes")
class CvhApiClientLiveTest {

    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(20);

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env " + key + " is required for live tests");
        }
        return v;
    }

    private static String envOrDefault(String key, String defaultValue) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }

    private static String referer() {
        return envOrDefault("CVH_LIVE_TEST_REFERER", "https://jut-su.works/");
    }

    private static CvhApiClient newClient() {
        return new CvhApiClient(CvhConfig.builder().build(), WebClient.builder());
    }

    @Test
    void titleEndpointReturnsTracks() {
        String titleId = requiredEnv("CVH_LIVE_TEST_TITLE_ID");
        String publisherId = requiredEnv("CVH_LIVE_TEST_PUBLISHER_ID");
        String aggregator = envOrDefault("CVH_LIVE_TEST_AGGREGATOR", "mali");

        List<CvhVoiceTrack> tracks =
                newClient()
                        .getTitleVoiceTracks(titleId, publisherId, aggregator, referer())
                        .block(BLOCKING_TIMEOUT);

        assertThat(tracks).as("voice tracks for titleId=%s", titleId).isNotNull().isNotEmpty();
        CvhVoiceTrack first = tracks.get(0);
        assertThat(first.cvhId()).isNotBlank();
        assertThat(first.vkId()).isNotBlank().matches("\\d+");
        assertThat(first.voiceStudio()).isNotBlank();
    }

    @Test
    void titleEndpointEveryTrackHasNonBlankFields() {
        String titleId = requiredEnv("CVH_LIVE_TEST_TITLE_ID");
        String publisherId = requiredEnv("CVH_LIVE_TEST_PUBLISHER_ID");

        List<CvhVoiceTrack> tracks =
                newClient()
                        .getTitleVoiceTracks(titleId, publisherId, "mali", referer())
                        .block(BLOCKING_TIMEOUT);

        assertThat(tracks)
                .allSatisfy(
                        t -> {
                            assertThat(t.vkId()).isNotBlank().matches("\\d+");
                            assertThat(t.cvhId()).isNotBlank();
                        });
    }

    @Test
    void videoEndpointReturnsSignedUrls() {
        String titleId = requiredEnv("CVH_LIVE_TEST_TITLE_ID");
        String publisherId = requiredEnv("CVH_LIVE_TEST_PUBLISHER_ID");
        String aggregator = envOrDefault("CVH_LIVE_TEST_AGGREGATOR", "mali");

        CvhApiClient api = newClient();
        List<CvhVoiceTrack> tracks =
                api.getTitleVoiceTracks(titleId, publisherId, aggregator, referer())
                        .block(BLOCKING_TIMEOUT);
        String vkId = tracks.get(0).vkId();

        CvhVideoSources s = api.getVideoSources(vkId, referer()).block(BLOCKING_TIMEOUT);

        assertThat(s).isNotNull();
        assertThat(s.vkId()).isPositive();
        assertThat(s.durationSec()).isPositive();
        assertThat(s.hlsUrl())
                .startsWith("https://")
                .contains(".m3u8")
                .contains("expires=")
                .contains("sig=")
                .contains("srcIp=");
        assertThat(s.expiresAt()).isNotNull().isAfter(Instant.now());
        assertThat(s.mp4_1080p()).startsWith("https://");
        assertThat(s.mp4_720p()).startsWith("https://");
        assertThat(s.dashUrl()).startsWith("https://");
    }

    @Test
    void videoEndpointExposesAllResolutionsAndThumbnail() {
        String titleId = requiredEnv("CVH_LIVE_TEST_TITLE_ID");
        String publisherId = requiredEnv("CVH_LIVE_TEST_PUBLISHER_ID");

        CvhApiClient api = newClient();
        String vkId =
                api.getTitleVoiceTracks(titleId, publisherId, "mali", referer())
                        .block(BLOCKING_TIMEOUT)
                        .get(0)
                        .vkId();

        CvhVideoSources s = api.getVideoSources(vkId, referer()).block(BLOCKING_TIMEOUT);

        assertThat(s.thumbnailUrl()).startsWith("https://");
        assertThat(s.mp4_480p()).startsWith("https://");
        assertThat(s.mp4_360p()).startsWith("https://");
        assertThat(s.mp4_240p()).startsWith("https://");
        // mp4_144p (tiny) is typical for any modern CVH-hosted title.
        assertThat(s.mp4_144p()).startsWith("https://");
        // Same expires timestamp across all variants — they share one signed-URL bundle.
        assertThat(s.hlsUrl()).contains("expires=" + s.expiresAt().toEpochMilli());
    }

    @Test
    void defaultRefererStillWorks() {
        // Empirically CVH does not enforce Referer server-side for the jut-su publisher: the
        // default config referer (player.cdnvideohub.com) succeeds. This test pins that behavior
        // so a future tightening on CVH's side surfaces as a test failure rather than silent
        // breakage in production.
        String titleId = requiredEnv("CVH_LIVE_TEST_TITLE_ID");
        String publisherId = requiredEnv("CVH_LIVE_TEST_PUBLISHER_ID");

        List<CvhVoiceTrack> tracks =
                newClient()
                        .getTitleVoiceTracks(titleId, publisherId, "mali")
                        .block(BLOCKING_TIMEOUT);
        assertThat(tracks).isNotNull().isNotEmpty();
    }

    @Test
    void unknownTitleIdReturnsEmptyTrackList() {
        // CVH plapi returns HTTP 204 (empty body) for unknown titleId rather than 404. The SDK
        // translates that into an empty track list — callers can branch on size() rather than
        // catching exceptions for the missing-title case.
        List<CvhVoiceTrack> tracks =
                newClient()
                        .getTitleVoiceTracks(
                                "999999999",
                                requiredEnv("CVH_LIVE_TEST_PUBLISHER_ID"),
                                "mali",
                                referer())
                        .block(BLOCKING_TIMEOUT);
        assertThat(tracks).isNotNull().isEmpty();
    }

    @Test
    void emptyVkIdSurfacesAsCvhVideoNotFound() {
        assertThatThrownBy(() -> newClient().getVideoSources("").block(BLOCKING_TIMEOUT))
                .satisfies(
                        ex -> {
                            Throwable root = ex;
                            while (root.getCause() != null) {
                                root = root.getCause();
                            }
                            assertThat(root).isInstanceOf(CvhApiException.class);
                            assertThat(((CvhApiException) root).errorCode())
                                    .isEqualTo("CVH_VIDEO_NOT_FOUND");
                        });
    }
}
