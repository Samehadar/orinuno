package com.orinuno.source.kodik.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kodik.client.http.RotatingUserAgentProvider;
import com.orinuno.source.kodik.configuration.KodikPlaywrightProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlaywrightVideoFetcherTest {

    @Test
    @DisplayName("isAvailable should return false when Playwright is disabled")
    void isAvailableShouldReturnFalseWhenDisabled() {
        var props = new KodikPlaywrightProperties();
        props.setEnabled(false);

        var fetcher = new PlaywrightVideoFetcher(props, new RotatingUserAgentProvider());
        fetcher.init();

        assertThat(fetcher.isAvailable()).isFalse();

        fetcher.destroy();
    }

    @Test
    @DisplayName("downloadVideo should return error when Playwright is not available")
    void downloadVideoShouldErrorWhenNotAvailable(@TempDir Path tempDir) {
        var props = new KodikPlaywrightProperties();
        props.setEnabled(false);

        var fetcher = new PlaywrightVideoFetcher(props, new RotatingUserAgentProvider());
        fetcher.init();

        Path target = tempDir.resolve("test.mp4");
        var result = fetcher.downloadVideo("//kodikplayer.com/seria/123", target);

        result.doOnError(
                        e ->
                                assertThat(e)
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("not available"))
                .subscribe();

        fetcher.destroy();
    }

    @Test
    @DisplayName("interceptVideoUrl should return error when Playwright is not available")
    void interceptVideoUrlShouldErrorWhenNotAvailable() {
        var props = new KodikPlaywrightProperties();
        props.setEnabled(false);

        var fetcher = new PlaywrightVideoFetcher(props, new RotatingUserAgentProvider());
        fetcher.init();

        var result = fetcher.interceptVideoUrl("//kodikplayer.com/seria/123");

        result.doOnError(
                        e ->
                                assertThat(e)
                                        .isInstanceOf(IllegalStateException.class)
                                        .hasMessageContaining("not available"))
                .subscribe();

        fetcher.destroy();
    }

    @Test
    @DisplayName("PlaywrightProperties defaults should be sensible")
    void defaultPropertiesShouldBeSensible() {
        var props = new KodikPlaywrightProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.isHeadless()).isTrue();
        assertThat(props.getPageTimeoutSeconds()).isEqualTo(30);
        assertThat(props.getNavigationTimeoutMs()).isEqualTo(15000);
        assertThat(props.getVideoWaitMs()).isEqualTo(30000);
        assertThat(props.getHlsConcurrency()).isEqualTo(16);
    }
}
