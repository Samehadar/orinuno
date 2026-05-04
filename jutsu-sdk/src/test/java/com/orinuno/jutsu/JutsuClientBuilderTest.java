package com.orinuno.jutsu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.drift.JutsuDriftHealth;
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

    @Test
    void exposesAFreshDriftDetectorByDefault() {
        // Drift snapshots are how orinuno-app's MultiSourceRanker decides whether to demote
        // jut.su; the default builder must expose a working detector even if the consumer
        // didn't explicitly wire one.
        JutsuClient client =
                JutsuClient.builder().config(JutsuConfig.builder().userAgent("ua").build()).build();
        assertThat(client.driftDetector()).isNotNull();
        assertThat(client.getDriftSnapshot().health()).isEqualTo(JutsuDriftHealth.HEALTHY);
        assertThat(client.getDriftSnapshot().recentEvents()).isEmpty();
    }

    @Test
    void honoursSharedDriftDetector() {
        // Sharing a detector across two clients (e.g. two Spring profiles) means dashboard /
        // alerts see one coherent view of jut.su's health.
        JutsuDriftDetector shared = new JutsuDriftDetector();
        JutsuClient a =
                JutsuClient.builder()
                        .config(JutsuConfig.builder().userAgent("ua").build())
                        .driftDetector(shared)
                        .build();
        JutsuClient b =
                JutsuClient.builder()
                        .config(JutsuConfig.builder().userAgent("ua").build())
                        .driftDetector(shared)
                        .build();
        assertThat(a.driftDetector()).isSameAs(b.driftDetector()).isSameAs(shared);
    }
}
