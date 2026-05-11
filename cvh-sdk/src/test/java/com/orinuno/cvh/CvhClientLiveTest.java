package com.orinuno.cvh;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.model.AnimeWithSources;
import com.orinuno.cvh.model.CvhVideoSources;
import com.orinuno.cvh.model.TrackWithSources;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Live integration tests against jut-su.works + plapi.cdnvideohub.com. Skipped unless {@code
 * CVH_LIVE_TESTS=1} is set in the environment.
 *
 * <p>Required env:
 *
 * <ul>
 *   <li>{@code CVH_LIVE_TESTS} — set to {@code 1|true|TRUE|yes} to enable.
 *   <li>{@code CVH_LIVE_TEST_URL} — full URL of a jut-su.works title page known to embed the CVH
 *       player ({@code <video-player>} element present). Required when live tests run.
 * </ul>
 *
 * <p>Optional env:
 *
 * <ul>
 *   <li>{@code CVH_LIVE_TEST_VK_ID} — numeric {@code vkId} to exercise the direct {@code
 *       /sv/video/{vkId}} fast-path. If unset, the test derives a vkId from the first track of the
 *       page decode result.
 * </ul>
 *
 * <p>These hit production CVH endpoints. Tokens returned by CVH are IP-bound and TTL ~24h — every
 * run consumes one signed URL per track. Run sparingly.
 */
@EnabledIfEnvironmentVariable(named = "CVH_LIVE_TESTS", matches = "1|true|TRUE|yes")
class CvhClientLiveTest {

    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Stable jut-su.works titles confirmed to embed CVH. Used as a portfolio to catch host-parser
     * drift on different page layouts (films, serials, ongoing).
     */
    static final String[] STABLE_URLS = {
        "https://jut-su.works/all-you-need-is-kill",
        "https://jut-su.works/shoujo-tachi-wa-kouya-wo-mezasu",
        "https://jut-su.works/konjiki-no-gash-bell",
        "https://jut-su.works/mahou-no-princess-minky-momo-yume-wo-dakishimete",
    };

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "Env "
                            + key
                            + " is required when CVH_LIVE_TESTS is enabled (set it to a real"
                            + " jut-su.works URL)");
        }
        return v;
    }

    private static CvhClient newClient() {
        return CvhClient.builder().build();
    }

    @Test
    void fullPipelineDecodesRealPage() {
        CvhClient client = newClient();
        String pageUrl = requiredEnv("CVH_LIVE_TEST_URL");

        CvhDecodeResult result = client.decode(pageUrl).block(BLOCKING_TIMEOUT);
        assertSuccessfulDecode(result, pageUrl);
    }

    /**
     * Pages without any embedded player (legacy jut-su titles that lost their Kodik/CVH iframe)
     * must still decode to a metadata-only payload with empty tracks — never throw, never null.
     * Confirms the pipeline degrades gracefully when {@code <video-player>} is absent.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "https://jut-su.works/shoujo-tachi-wa-kouya-wo-mezasu",
                "https://jut-su.works/konjiki-no-gash-bell",
                "https://jut-su.works/mahou-no-princess-minky-momo-yume-wo-dakishimete",
            })
    void playerlessPagesDecodeToMetadataWithEmptyTracks(String pageUrl) {
        CvhDecodeResult result = newClient().decode(pageUrl).block(BLOCKING_TIMEOUT);
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.value().metadata().title()).isNotBlank();
        assertThat(result.value().metadata().cvhTitleId()).isNull();
        assertThat(result.value().tracks()).isEmpty();
    }

    @Test
    void randomEndpointResolvesToSomeTitle() {
        // jut-su.works/random redirects to a random title slug. Whichever it lands on, the pipeline
        // must produce a successful CvhDecodeResult with a non-blank title (CVH may or may not be
        // embedded — both are valid).
        CvhDecodeResult result =
                newClient().decode("https://jut-su.works/random").block(BLOCKING_TIMEOUT);
        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.value().metadata().title()).isNotBlank();
    }

    @Test
    void unsupportedHostShortCircuitsLive() {
        // Real network: confirm the host registry rejects without making any HTTP call.
        CvhDecodeResult result =
                newClient().decode("https://example.com/foo").block(BLOCKING_TIMEOUT);
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(CvhErrorCodes.CVH_UNSUPPORTED_HOST);
    }

    @Test
    void cacheReturnsSameSourcesWithinTtl() {
        CvhClient client = newClient();
        String pageUrl = requiredEnv("CVH_LIVE_TEST_URL");

        CvhDecodeResult firstResult = client.decode(pageUrl).block(BLOCKING_TIMEOUT);
        assertThat(firstResult).isNotNull();
        assertThat(firstResult.success()).isTrue();
        String vkId = firstResult.value().tracks().get(0).track().vkId();
        CvhVideoSources first = firstResult.value().tracks().get(0).sources();

        CvhVideoSources cached = client.getSourcesByVkId(vkId).block(BLOCKING_TIMEOUT);
        assertThat(cached).isNotNull();
        assertThat(cached.hlsUrl()).isEqualTo(first.hlsUrl());
        assertThat(cached.expiresAt()).isEqualTo(first.expiresAt());
    }

    @Test
    void directVkIdFetchExposesAllMp4Qualities() {
        String vkId = System.getenv("CVH_LIVE_TEST_VK_ID");
        String pageUrl = requiredEnv("CVH_LIVE_TEST_URL");
        if (vkId == null || vkId.isBlank()) {
            CvhDecodeResult r = newClient().decode(pageUrl).block(BLOCKING_TIMEOUT);
            assertThat(r).isNotNull();
            assertThat(r.success()).isTrue();
            vkId = r.value().tracks().get(0).track().vkId();
        }

        // Direct fast-path needs the host-derived referer; pass it explicitly because there is no
        // page in this call to derive it from.
        CvhVideoSources s =
                newClient().getSourcesByVkId(vkId, "https://jut-su.works/").block(BLOCKING_TIMEOUT);
        assertThat(s).isNotNull();
        assertThat(s.vkId()).isPositive();
        assertThat(s.durationSec()).isNotNull().isPositive();
        assertThat(s.mp4_1080p()).startsWith("https://");
        assertThat(s.mp4_720p()).startsWith("https://");
        assertThat(s.mp4_480p()).startsWith("https://");
        assertThat(s.hlsUrl()).contains(".m3u8");
        assertThat(s.dashUrl()).startsWith("https://");
    }

    private static void assertSuccessfulDecode(CvhDecodeResult result, String pageUrl) {
        assertThat(result).as("decode result for %s", pageUrl).isNotNull();
        assertThat(result.success())
                .as("decode success for %s, errorCode=%s", pageUrl, result.errorCode())
                .isTrue();
        AnimeWithSources value = result.value();
        assertThat(value).isNotNull();
        assertThat(value.metadata().title()).as("title").isNotBlank();
        assertThat(value.metadata().posterUrl()).as("posterUrl").isNotBlank();
        assertThat(value.metadata().cvhTitleId()).as("cvhTitleId").isNotBlank();
        assertThat(value.metadata().cvhAggregator()).as("cvhAggregator").isNotBlank();
        assertThat(value.tracks()).as("tracks").isNotEmpty();

        TrackWithSources first = value.tracks().get(0);
        assertThat(first.track().vkId()).isNotBlank();
        CvhVideoSources s = first.sources();
        assertThat(s).isNotNull();
        assertThat(s.hlsUrl())
                .startsWith("https://")
                .contains("expires=")
                .contains("sig=")
                .contains("srcIp=");
        assertThat(s.expiresAt()).isNotNull();
        assertThat(s.expiresAt()).as("expiresAt must be in the future").isAfter(Instant.now());
    }
}
