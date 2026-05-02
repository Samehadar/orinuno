package com.orinuno.service.provider.sibnet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SibnetSourceParserTest {

    @Test
    void extractsFromShellUrl() {
        assertThat(
                        SibnetSourceParser.extractVideoId(
                                "https://video.sibnet.ru/shell.php?videoid=1234567"))
                .contains(1234567L);
    }

    @Test
    void extractsFromShellUrlWithExtraParams() {
        assertThat(
                        SibnetSourceParser.extractVideoId(
                                "https://video.sibnet.ru/shell.php?start=1&videoid=42&autoplay=1"))
                .contains(42L);
    }

    @Test
    void extractsFromPageUrlWithSlug() {
        assertThat(
                        SibnetSourceParser.extractVideoId(
                                "https://video.sibnet.ru/video999-naruto-episode-1.html"))
                .contains(999L);
    }

    @Test
    void extractsFromPageUrlWithoutSlug() {
        assertThat(SibnetSourceParser.extractVideoId("https://video.sibnet.ru/video888.html"))
                .contains(888L);
    }

    @Test
    void rejectsUnrelatedUrls() {
        assertThat(SibnetSourceParser.extractVideoId("https://kodik.cc/seria/1/abc")).isEmpty();
        assertThat(SibnetSourceParser.extractVideoId(null)).isEmpty();
        assertThat(SibnetSourceParser.extractVideoId("")).isEmpty();
        assertThat(SibnetSourceParser.extractVideoId("https://video.sibnet.ru/nope.html"))
                .isEmpty();
    }

    @Test
    void canonicalizesToIframeShape() {
        assertThat(SibnetSourceParser.toIframeUrl(123L))
                .isEqualTo("https://video.sibnet.ru/shell.php?videoid=123");
    }
}
