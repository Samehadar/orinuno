package com.orinuno.jutsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JutsuConfigTest {

    @Test
    void defaultsMatchOrinunoAppShipDefaults() {
        // The SDK's defaults are deliberately the same as orinuno-app's application.yml: a
        // consumer who calls .builder().build() with no overrides gets the production-tested
        // shape rather than something laxer.
        JutsuConfig cfg = JutsuConfig.builder().build();
        assertThat(cfg.baseUrl()).isEqualTo("https://jut.su");
        assertThat(cfg.rateLimitRps()).isEqualTo(1.0);
        assertThat(cfg.sessionTtl()).isEqualTo(Duration.ofMinutes(240));
        assertThat(cfg.loginTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(cfg.hasCredentials()).isFalse();
    }

    @Test
    void hasCredentialsRequiresBothFields() {
        assertThat(JutsuConfig.builder().credentials("u", "p").build().hasCredentials()).isTrue();
        assertThat(JutsuConfig.builder().credentials("u", "").build().hasCredentials()).isFalse();
        assertThat(JutsuConfig.builder().credentials("u", null).build().hasCredentials()).isFalse();
        assertThat(JutsuConfig.builder().credentials(null, "p").build().hasCredentials()).isFalse();
    }

    @Test
    void rejectsZeroRateLimit() {
        assertThatThrownBy(() -> JutsuConfig.builder().rateLimitRps(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rateLimitRps");
    }

    @Test
    void rejectsBlankUserAgent() {
        // Blank UA gets curl/wget treatment from jut.su's bot detection — we want the consumer
        // to be told at construction time, not after the first 403/404 in production.
        assertThatThrownBy(() -> JutsuConfig.builder().userAgent(" ").build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userAgent");
    }
}
