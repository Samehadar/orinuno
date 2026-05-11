package com.orinuno.aksor;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.aksor.model.AksorAnime;
import com.orinuno.aksor.model.AksorEpisode;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live integration tests against {@code old.yummyani.me} + {@code player.aksor.tv}. Skipped unless
 * {@code AKSOR_LIVE_TESTS=1|true|yes}.
 *
 * <p>Required env:
 *
 * <ul>
 *   <li>{@code AKSOR_LIVE_TESTS} — enable flag.
 *   <li>{@code AKSOR_LIVE_TEST_URL} — yummyani page URL (e.g. {@code
 *       https://old.yummyani.me/catalog/item/monolog-farmatsevta}).
 * </ul>
 *
 * <p>Optional env:
 *
 * <ul>
 *   <li>{@code AKSOR_LIVE_TEST_HASH} — single 32-hex hash to exercise the direct API path.
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "AKSOR_LIVE_TESTS", matches = "1|true|TRUE|yes")
class AksorClientLiveTest {

    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(45);

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env " + key + " is required for live tests");
        }
        return v;
    }

    @Test
    void fullPipelineDecodesRealYummyPage() {
        AksorClient client = AksorClient.builder().build();
        AksorDecodeResult result =
                client.decode(requiredEnv("AKSOR_LIVE_TEST_URL")).block(BLOCKING_TIMEOUT);
        assertThat(result).isNotNull();
        assertThat(result.success())
                .as("decode success, errorCode=%s", result.errorCode())
                .isTrue();
        AksorAnime anime = result.value();
        assertThat(anime.animeId()).isNotBlank().matches("\\d+");
        assertThat(anime.title()).isNotBlank();
        assertThat(anime.episodes()).isNotEmpty();
        AksorEpisode first = anime.episodes().get(0);
        assertThat(first.hash()).matches("[a-f0-9]{32}");
        assertThat(first.qualities())
                .as("episode qualities must be enriched by pipeline")
                .isNotNull();
        assertThat(first.qualities().bestAvailable()).startsWith("https://").endsWith(".mpd");
    }

    @Test
    void directApiCallReturnsQualities() {
        String hash = System.getenv("AKSOR_LIVE_TEST_HASH");
        AksorClient client = AksorClient.builder().build();
        if (hash == null || hash.isBlank()) {
            AksorDecodeResult r =
                    client.decode(requiredEnv("AKSOR_LIVE_TEST_URL")).block(BLOCKING_TIMEOUT);
            assertThat(r).isNotNull();
            assertThat(r.success()).isTrue();
            hash = r.value().episodes().get(0).hash();
        }
        var q = client.getQualitiesByHash(hash, "https://old.yummyani.me/").block(BLOCKING_TIMEOUT);
        assertThat(q).isNotNull();
        assertThat(q.bestAvailable()).startsWith("https://").endsWith(".mpd");
    }

    @Test
    void unsupportedHostShortCircuits() {
        AksorClient client = AksorClient.builder().build();
        AksorDecodeResult r = client.decode("https://other.test/foo").block(BLOCKING_TIMEOUT);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(AksorErrorCodes.AKSOR_UNSUPPORTED_HOST);
    }
}
