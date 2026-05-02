package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.orinuno.service.decoder.DecoderPathCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the per-netloc video-info-path cache (DECODE-7 added netloc keying; DECODE-2
 * added DB persistence; this test pins the in-memory invariants both depend on).
 *
 * <p>Before DECODE-7 the cache was a single global slot; iframes from different netlocs would cause
 * the cache to flap. After DECODE-7 the cache is keyed by netloc and reads from one netloc never
 * affect another. DECODE-2 promoted the static map to a Spring bean ({@link DecoderPathCache}) so
 * we instantiate it directly instead of poking a static via reflection.
 */
class KodikVideoDecoderCacheTest {

    private DecoderPathCache cache;

    @BeforeEach
    void freshCache() {
        cache = new DecoderPathCache(null);
    }

    @Test
    @DisplayName("snapshot of a fresh cache is empty")
    void cacheStartsEmpty() {
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("put stores per-netloc; different netlocs do not overwrite each other")
    void cachePerNetlocDoesNotFlap() {
        cache.put("kodikplayer.com", "/ftor");
        cache.put("kodik.cc", "/kor");
        cache.put("kodikv.cc", "/gvi");

        assertThat(cache.snapshot())
                .containsEntry("kodikplayer.com", "/ftor")
                .containsEntry("kodik.cc", "/kor")
                .containsEntry("kodikv.cc", "/gvi")
                .hasSize(3);

        cache.put("kodikplayer.com", "/seria");

        assertThat(cache.snapshot())
                .containsEntry("kodikplayer.com", "/seria")
                .containsEntry("kodik.cc", "/kor")
                .containsEntry("kodikv.cc", "/gvi");
    }

    @Test
    @DisplayName("put ignores blank netloc, blank path, or paths without leading slash")
    void putRejectsInvalidInputs() {
        cache.put(null, "/ftor");
        cache.put("", "/ftor");
        cache.put("kodik.cc", null);
        cache.put("kodik.cc", "");
        cache.put("kodik.cc", "noslash");

        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("netloc keys are case-insensitive on read and write")
    void netlocKeyIsCaseInsensitive() {
        cache.put("Kodik.CC", "/ftor");

        assertThat(cache.snapshot()).containsKey("kodik.cc").doesNotContainKey("Kodik.CC");
        assertThat(cache.get("KODIK.cc")).contains("/ftor");
    }

    @Test
    @DisplayName("get on missing netloc returns Optional.empty()")
    void getReturnsEmptyOnMiss() {
        assertThat(cache.get("not.cached")).isEmpty();
        assertThat(cache.get(null)).isEmpty();
        assertThat(cache.get("")).isEmpty();
    }

    @Test
    @DisplayName("clear wipes the cache")
    void clearWipesCache() {
        cache.put("kodik.cc", "/ftor");
        cache.put("kodikplayer.com", "/kor");

        cache.clear();

        assertThat(cache.snapshot()).isEmpty();
    }
}
