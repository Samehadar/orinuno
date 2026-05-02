package com.orinuno.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the per-netloc video-info-path cache introduced in DECODE-7.
 *
 * <p>Before DECODE-7 the cache was a single global slot; iframes from different netlocs would cause
 * the cache to flap. After DECODE-7 the cache is keyed by netloc and reads from one netloc never
 * affect another.
 */
class KodikVideoDecoderCacheTest {

    @BeforeEach
    @AfterEach
    void resetCache() {
        KodikVideoDecoderService.clearVideoInfoPathCache();
    }

    @Test
    @DisplayName("clearVideoInfoPathCache leaves the cache empty")
    void cacheStartsAndEndsEmpty() {
        assertThat(KodikVideoDecoderService.snapshotVideoInfoPathCache()).isEmpty();
    }

    @Test
    @DisplayName(
            "cacheVideoInfoPath stores per-netloc; different netlocs do not overwrite each other")
    void cachePerNetlocDoesNotFlap() throws Exception {
        invokeCache("kodikplayer.com", "/ftor");
        invokeCache("kodik.cc", "/kor");
        invokeCache("kodikv.cc", "/gvi");

        assertThat(KodikVideoDecoderService.snapshotVideoInfoPathCache())
                .containsEntry("kodikplayer.com", "/ftor")
                .containsEntry("kodik.cc", "/kor")
                .containsEntry("kodikv.cc", "/gvi")
                .hasSize(3);

        // Updating one netloc must not touch the others.
        invokeCache("kodikplayer.com", "/seria");
        assertThat(KodikVideoDecoderService.snapshotVideoInfoPathCache())
                .containsEntry("kodikplayer.com", "/seria")
                .containsEntry("kodik.cc", "/kor")
                .containsEntry("kodikv.cc", "/gvi");
    }

    @Test
    @DisplayName("cacheVideoInfoPath ignores blank netloc, blank path, or non-/ paths")
    void cacheRejectsInvalidInputs() throws Exception {
        invokeCache(null, "/ftor");
        invokeCache("", "/ftor");
        invokeCache("kodik.cc", null);
        invokeCache("kodik.cc", "");
        invokeCache("kodik.cc", "noslash");

        assertThat(KodikVideoDecoderService.snapshotVideoInfoPathCache()).isEmpty();
    }

    @Test
    @DisplayName("cacheVideoInfoPath is case-insensitive on netloc")
    void netlocKeyIsCaseInsensitive() throws Exception {
        invokeCache("Kodik.CC", "/ftor");

        assertThat(KodikVideoDecoderService.snapshotVideoInfoPathCache())
                .containsKey("kodik.cc")
                .doesNotContainKey("Kodik.CC");
    }

    private static void invokeCache(String netloc, String path) throws Exception {
        Method m =
                KodikVideoDecoderService.class.getDeclaredMethod(
                        "cacheVideoInfoPath", String.class, String.class);
        m.setAccessible(true);
        m.invoke(null, netloc, path);
    }
}
