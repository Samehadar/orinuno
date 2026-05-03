package com.orinuno.aniboom.parser;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AniboomSourceParserTest {

    @Test
    void extractsEmbedId() {
        assertThat(AniboomSourceParser.extractEmbedId("https://aniboom.one/embed/abc123"))
                .contains("abc123");
        assertThat(AniboomSourceParser.extractEmbedId("https://aniboom.one/embed/x_y-z?autoplay=1"))
                .contains("x_y-z");
    }

    @Test
    void rejectsUnrelatedUrls() {
        assertThat(AniboomSourceParser.extractEmbedId("https://kodik.cc/x")).isEmpty();
        assertThat(AniboomSourceParser.extractEmbedId(null)).isEmpty();
        assertThat(AniboomSourceParser.extractEmbedId("")).isEmpty();
    }

    @Test
    void canonicalisesToEmbedShape() {
        assertThat(AniboomSourceParser.toEmbedUrl("xyz"))
                .isEqualTo("https://aniboom.one/embed/xyz");
    }
}
