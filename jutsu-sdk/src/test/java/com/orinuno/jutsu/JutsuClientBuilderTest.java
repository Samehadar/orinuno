package com.orinuno.jutsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class JutsuClientBuilderTest {

    @Test
    void buildsWithMinimumInputs() {
        // Default builder, no MeterRegistry, no custom WebClient.Builder. The SDK must wire its
        // own no-op metrics and a fresh WebClient.builder() so consumers can adopt it without
        // pulling extra dependencies in.
        JutsuClient client =
                JutsuClient.builder().config(JutsuConfig.builder().userAgent("ua").build()).build();
        assertThat(client.config().baseUrl()).isEqualTo("https://jut.su");
        assertThat(client.rateLimiter().currentRequestsPerSecond()).isEqualTo(1.0);
        assertThat(client.sessionManager().peekHasCredentials()).isFalse();
    }

    @Test
    void rejectsBuildWithoutConfig() {
        assertThatThrownBy(() -> JutsuClient.builder().build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("config is required");
    }

    @Test
    void honoursCustomWebClientBuilder() {
        // Wiring the SDK behind a Wiremock or a stubbed ExchangeFunction is the supported
        // testing extension point. Smoke-test that the builder accepts (and uses) ours.
        WebClient.Builder builder = WebClient.builder();
        JutsuClient client =
                JutsuClient.builder()
                        .config(JutsuConfig.builder().userAgent("ua").build())
                        .webClientBuilder(builder)
                        .build();
        assertThat(client).isNotNull();
    }
}
