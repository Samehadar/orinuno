package com.orinuno.aksor.host.yummy;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.aksor.drift.AksorDriftDetector;
import com.orinuno.aksor.drift.AksorDriftSignal;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link YummyAniHost#buildAnime} emits the expected drift signals when it hits
 * broken {@code /api/anime/{id}/videos} responses. The test lives in the same package as the host
 * so it can construct the package-private {@link YummyAniHost.PageMeta} record.
 */
class YummyAniHostDriftTest {

    private static YummyAniHost.PageMeta meta() {
        return new YummyAniHost.PageMeta("10531", "slug", "Title", "https://p.jpg");
    }

    @Test
    void responseNotArrayEmitsSignal() {
        AksorDriftDetector d = new AksorDriftDetector();
        try {
            YummyAniHost.buildAnime(
                    meta(), "https://old.yummyani.me/x", "{\"response\":{\"oops\":1}}", d);
        } catch (Exception ignored) {
            // expected: throws AKSOR_PAGE_PARSE_ERROR
        }
        assertThat(d.snapshot().count(AksorDriftSignal.YUMMY_VIDEOS_RESPONSE_NOT_ARRAY))
                .isEqualTo(1);
    }

    @Test
    void aksorEntryWithoutHashEmitsSignal() {
        AksorDriftDetector d = new AksorDriftDetector();
        String json =
                "{\"response\":[{"
                        + "\"video_id\":1,\"number\":\"1\","
                        + "\"data\":{\"player\":\"Плеер Aksor\",\"dubbing\":\"X\"},"
                        + "\"iframe_url\":\"https://player.aksor.tv/video/not-a-hash\","
                        + "\"skips\":{}"
                        + "}]}";
        try {
            YummyAniHost.buildAnime(meta(), "https://old.yummyani.me/x", json, d);
        } catch (Exception ignored) {
            // expected: throws AKSOR_NO_EPISODES after filtering out the unhashed entry
        }
        assertThat(d.snapshot().count(AksorDriftSignal.YUMMY_EPISODE_NO_HASH)).isEqualTo(1);
    }

    @Test
    void unknownPlayerEmitsSignal() {
        AksorDriftDetector d = new AksorDriftDetector();
        String json =
                "{\"response\":[{"
                        + "\"video_id\":1,\"number\":\"1\","
                        + "\"data\":{\"player\":\"Какой-то Другой\",\"dubbing\":\"X\"},"
                        + "\"iframe_url\":\"https://other.test/foo\","
                        + "\"skips\":{}"
                        + "}]}";
        try {
            YummyAniHost.buildAnime(meta(), "https://old.yummyani.me/x", json, d);
        } catch (Exception ignored) {
            // expected: AKSOR_NO_EPISODES — but we care about the drift signal it emitted first
        }
        assertThat(d.snapshot().count(AksorDriftSignal.YUMMY_EPISODE_UNKNOWN_PLAYER)).isEqualTo(1);
    }

    @Test
    void nullPlayerSilentlySkippedNoSignal() {
        // Entries with missing player field are normal (other-host placeholders) — not drift.
        AksorDriftDetector d = new AksorDriftDetector();
        String json =
                "{\"response\":[{"
                        + "\"video_id\":1,\"number\":\"1\","
                        + "\"data\":{\"dubbing\":\"X\"},"
                        + "\"iframe_url\":\"https://x/y\","
                        + "\"skips\":{}"
                        + "}]}";
        try {
            YummyAniHost.buildAnime(meta(), "https://old.yummyani.me/x", json, d);
        } catch (Exception ignored) {
            // expected: AKSOR_NO_EPISODES
        }
        assertThat(d.snapshot().count(AksorDriftSignal.YUMMY_EPISODE_UNKNOWN_PLAYER)).isZero();
    }
}
