package com.orinuno.cvh.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.cvh.CvhConfig;
import com.orinuno.cvh.model.CvhVideoSources;
import com.orinuno.cvh.model.CvhVoiceTrack;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Live tests for {@link CvhVideoSourcesCache} against real CVH. Verifies cache lifecycle (hit /
 * miss / invalidate / size) and the {@code referer} overload, both required for the SDK's caching
 * contract to be useful under production conditions.
 *
 * <p>Skipped unless {@code CVH_LIVE_TESTS=1}. Required env: {@code CVH_LIVE_TEST_TITLE_ID}, {@code
 * CVH_LIVE_TEST_PUBLISHER_ID}.
 */
@EnabledIfEnvironmentVariable(named = "CVH_LIVE_TESTS", matches = "1|true|TRUE|yes")
class CvhVideoSourcesCacheLiveTest {

    private static final Duration BLOCKING_TIMEOUT = Duration.ofSeconds(20);
    private static final String JUTSU_REFERER = "https://jut-su.works/";

    private static String requiredEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env " + key + " is required for live tests");
        }
        return v;
    }

    private static String firstVkId(CvhApiClient api) {
        String titleId = requiredEnv("CVH_LIVE_TEST_TITLE_ID");
        String publisherId = requiredEnv("CVH_LIVE_TEST_PUBLISHER_ID");
        List<CvhVoiceTrack> tracks =
                api.getTitleVoiceTracks(titleId, publisherId, "mali", JUTSU_REFERER)
                        .block(BLOCKING_TIMEOUT);
        return tracks.get(0).vkId();
    }

    private static CvhApiClient newApi(CvhConfig cfg) {
        return new CvhApiClient(cfg, WebClient.builder());
    }

    @Test
    void getOrFetchPopulatesCacheThenServesFromMemory() {
        CvhConfig cfg = CvhConfig.builder().build();
        CvhApiClient api = newApi(cfg);
        CvhVideoSourcesCache cache = new CvhVideoSourcesCache(api, cfg);
        String vkId = firstVkId(api);

        assertThat(cache.size()).isZero();
        CvhVideoSources first = cache.getOrFetch(vkId, JUTSU_REFERER).block(BLOCKING_TIMEOUT);
        assertThat(cache.size()).isOne();
        assertThat(first.hlsUrl()).startsWith("https://");

        // Second call returns the same instance (cached) without making a network call.
        CvhVideoSources second = cache.getOrFetch(vkId, JUTSU_REFERER).block(BLOCKING_TIMEOUT);
        assertThat(second).isSameAs(first);
        assertThat(cache.size()).isOne();
    }

    @Test
    void invalidateForcesRefetch() {
        CvhConfig cfg = CvhConfig.builder().build();
        CvhApiClient api = newApi(cfg);
        CvhVideoSourcesCache cache = new CvhVideoSourcesCache(api, cfg);
        String vkId = firstVkId(api);

        CvhVideoSources first = cache.getOrFetch(vkId, JUTSU_REFERER).block(BLOCKING_TIMEOUT);
        assertThat(cache.size()).isOne();

        cache.invalidate(vkId);
        assertThat(cache.size()).isZero();

        CvhVideoSources second = cache.getOrFetch(vkId, JUTSU_REFERER).block(BLOCKING_TIMEOUT);
        assertThat(cache.size()).isOne();
        assertThat(second).isNotSameAs(first);
        // Same upstream content — vkId binds to one resource, so URL bundles match for the same TTL
        // window.
        assertThat(second.vkId()).isEqualTo(first.vkId());
        assertThat(second.hlsUrl()).startsWith("https://");
    }

    @Test
    void noArgOverloadUsesConfigDefaultRefererAndStillWorks() {
        // CVH plapi currently does not enforce Referer for the jut-su publisher, so the no-arg
        // overload (which falls back to CvhConfig.referer) succeeds. Pin this behavior so any
        // future Referer tightening surfaces here.
        CvhConfig cfg = CvhConfig.builder().build();
        CvhApiClient api = newApi(cfg);
        CvhVideoSourcesCache cache = new CvhVideoSourcesCache(api, cfg);
        String vkId = firstVkId(api);

        CvhVideoSources s = cache.getOrFetch(vkId).block(BLOCKING_TIMEOUT);
        assertThat(s).isNotNull();
        assertThat(s.hlsUrl()).startsWith("https://");
        assertThat(cache.size()).isOne();
    }
}
