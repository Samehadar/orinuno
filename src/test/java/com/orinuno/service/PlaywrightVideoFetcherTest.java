package com.orinuno.service;

import com.orinuno.configuration.OrinunoProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightVideoFetcherTest {

    @Test
    @DisplayName("isAvailable should return false when Playwright is disabled")
    void isAvailableShouldReturnFalseWhenDisabled() {
        var props = new OrinunoProperties();
        props.getPlaywright().setEnabled(false);

        var fetcher = new PlaywrightVideoFetcher(props);
        fetcher.init();

        assertThat(fetcher.isAvailable()).isFalse();

        fetcher.destroy();
    }

    @Test
    @DisplayName("downloadVideo should return error when Playwright is not available")
    void downloadVideoShouldErrorWhenNotAvailable(@TempDir Path tempDir) {
        var props = new OrinunoProperties();
        props.getPlaywright().setEnabled(false);

        var fetcher = new PlaywrightVideoFetcher(props);
        fetcher.init();

        Path target = tempDir.resolve("test.mp4");
        var result = fetcher.downloadVideo("//kodikplayer.com/seria/123", target);

        result.doOnError(e -> assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not available"))
                .subscribe();

        fetcher.destroy();
    }

    @Test
    @DisplayName("interceptVideoUrl should return error when Playwright is not available")
    void interceptVideoUrlShouldErrorWhenNotAvailable() {
        var props = new OrinunoProperties();
        props.getPlaywright().setEnabled(false);

        var fetcher = new PlaywrightVideoFetcher(props);
        fetcher.init();

        var result = fetcher.interceptVideoUrl("//kodikplayer.com/seria/123");

        result.doOnError(e -> assertThat(e)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("not available"))
                .subscribe();

        fetcher.destroy();
    }

    @Test
    @DisplayName("PlaywrightProperties defaults should be sensible")
    void defaultPropertiesShouldBeSensible() {
        var props = new OrinunoProperties.PlaywrightProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isHeadless()).isTrue();
        assertThat(props.getPageTimeoutSeconds()).isEqualTo(30);
        assertThat(props.getNavigationTimeoutMs()).isEqualTo(15000);
        assertThat(props.getVideoWaitMs()).isEqualTo(20000);
        assertThat(props.getHlsConcurrency()).isEqualTo(8);
    }
}
