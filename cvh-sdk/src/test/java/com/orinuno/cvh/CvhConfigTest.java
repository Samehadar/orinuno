package com.orinuno.cvh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CvhConfigTest {

    @Test
    void defaultsAreSane() {
        CvhConfig c = CvhConfig.builder().build();
        assertThat(c.plapiBaseUrl()).isEqualTo("https://plapi.cdnvideohub.com");
        assertThat(c.playerBaseUrl()).isEqualTo("https://player.cdnvideohub.com");
        assertThat(c.referer()).isEqualTo("https://player.cdnvideohub.com/");
        assertThat(c.defaultAggregator()).isEqualTo("mali");
        assertThat(c.tokenRefreshMarginMinutes()).isEqualTo(30);
        assertThat(c.userAgent()).contains("Chrome");
    }

    @Test
    void blankRefererIsAllowedAsFallbackPlaceholder() {
        // Pipeline derives a per-call referer from the host page URL, so an empty fallback is fine.
        // Only null is rejected.
        CvhConfig c = CvhConfig.builder().referer("").build();
        assertThat(c.referer()).isEmpty();
        assertThatThrownBy(() -> CvhConfig.builder().referer(null).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankUserAgentIsRejected() {
        assertThatThrownBy(() -> CvhConfig.builder().userAgent("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userAgent");
    }

    @Test
    void negativeMarginIsRejected() {
        assertThatThrownBy(() -> CvhConfig.builder().tokenRefreshMarginMinutes(-1).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankDefaultAggregatorIsRejected() {
        assertThatThrownBy(() -> CvhConfig.builder().defaultAggregator("").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overrideFieldsFlowThrough() {
        CvhConfig c =
                CvhConfig.builder()
                        .plapiBaseUrl("https://example.test")
                        .playerBaseUrl("https://player.test")
                        .referer("https://player.test/")
                        .userAgent("UA")
                        .defaultAggregator("foo")
                        .tokenRefreshMarginMinutes(5)
                        .maxCacheEntries(50)
                        .build();
        assertThat(c.plapiBaseUrl()).isEqualTo("https://example.test");
        assertThat(c.defaultAggregator()).isEqualTo("foo");
        assertThat(c.tokenRefreshMarginMinutes()).isEqualTo(5);
        assertThat(c.maxCacheEntries()).isEqualTo(50);
    }

    @Test
    void plapiBaseUrlMustBeHttps() {
        assertThatThrownBy(
                        () ->
                                CvhConfig.builder()
                                        .plapiBaseUrl("http://plapi.cdnvideohub.com")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void playerBaseUrlMustBeHttps() {
        assertThatThrownBy(
                        () ->
                                CvhConfig.builder()
                                        .playerBaseUrl("http://player.cdnvideohub.com")
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void maxCacheEntriesMustBePositive() {
        assertThatThrownBy(() -> CvhConfig.builder().maxCacheEntries(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
