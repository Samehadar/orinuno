package com.orinuno.aksor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AksorConfigTest {

    @Test
    void defaultsAreSane() {
        AksorConfig c = AksorConfig.builder().build();
        assertThat(c.apiBaseUrl()).isEqualTo("https://player.aksor.tv");
        assertThat(c.playerBaseUrl()).isEqualTo("https://player.aksor.tv");
        assertThat(c.referer()).isEqualTo("https://player.aksor.tv/");
        assertThat(c.userAgent()).contains("Chrome");
        assertThat(c.episodeFetchConcurrency()).isEqualTo(4);
        assertThat(c.requestTimeoutSeconds()).isEqualTo(20);
    }

    @Test
    void httpApiBaseRejected() {
        assertThatThrownBy(() -> AksorConfig.builder().apiBaseUrl("http://x.test").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void blankRefererPlaceholderAllowed() {
        AksorConfig c = AksorConfig.builder().referer("").build();
        assertThat(c.referer()).isEmpty();
        assertThatThrownBy(() -> AksorConfig.builder().referer(null).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroConcurrencyRejected() {
        assertThatThrownBy(() -> AksorConfig.builder().episodeFetchConcurrency(0).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
