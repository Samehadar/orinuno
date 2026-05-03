package com.orinuno.aniboom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AniboomConfigTest {

    @Test
    void defaultsAreReasonable() {
        AniboomConfig c = AniboomConfig.builder().build();
        assertThat(c.baseUrl()).isEqualTo("https://aniboom.one");
        assertThat(c.referer()).isEqualTo("https://animego.org/");
        assertThat(c.userAgent()).contains("Mozilla/5.0");
    }

    @Test
    void overrideIsHonoured() {
        AniboomConfig c =
                AniboomConfig.builder()
                        .baseUrl("https://example.org")
                        .referer("https://example.com/")
                        .userAgent("test-agent/1.0")
                        .build();
        assertThat(c.baseUrl()).isEqualTo("https://example.org");
        assertThat(c.referer()).isEqualTo("https://example.com/");
        assertThat(c.userAgent()).isEqualTo("test-agent/1.0");
    }

    @Test
    void blankFieldsAreRejected() {
        assertThatThrownBy(() -> AniboomConfig.builder().baseUrl("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
        assertThatThrownBy(() -> AniboomConfig.builder().referer("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("referer");
        assertThatThrownBy(() -> AniboomConfig.builder().userAgent("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userAgent");
    }
}
