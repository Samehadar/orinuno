package com.orinuno.sibnet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SibnetConfigTest {

    @Test
    void defaultsAreReasonable() {
        SibnetConfig c = SibnetConfig.builder().build();
        assertThat(c.baseUrl()).isEqualTo("https://video.sibnet.ru");
        assertThat(c.referer()).isEqualTo("https://video.sibnet.ru/");
        assertThat(c.userAgent()).contains("Mozilla/5.0");
    }

    @Test
    void overrideIsHonoured() {
        SibnetConfig c =
                SibnetConfig.builder()
                        .baseUrl("https://example.org")
                        .referer("https://example.org/")
                        .userAgent("test-agent/1.0")
                        .build();
        assertThat(c.baseUrl()).isEqualTo("https://example.org");
        assertThat(c.referer()).isEqualTo("https://example.org/");
        assertThat(c.userAgent()).isEqualTo("test-agent/1.0");
    }

    @Test
    void blankBaseUrlIsRejected() {
        assertThatThrownBy(() -> SibnetConfig.builder().baseUrl("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseUrl");
    }

    @Test
    void blankRefererIsRejected() {
        assertThatThrownBy(() -> SibnetConfig.builder().referer("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("referer");
    }

    @Test
    void blankUserAgentIsRejected() {
        assertThatThrownBy(() -> SibnetConfig.builder().userAgent("").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userAgent");
    }
}
