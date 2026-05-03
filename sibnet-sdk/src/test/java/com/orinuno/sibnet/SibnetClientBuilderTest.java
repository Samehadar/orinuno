package com.orinuno.sibnet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class SibnetClientBuilderTest {

    @Test
    void builderProducesConfiguredClient() {
        SibnetConfig config = SibnetConfig.builder().userAgent("test/1.0").build();
        SibnetClient client =
                SibnetClient.builder().config(config).webClientBuilder(WebClient.builder()).build();
        assertThat(client.config()).isSameAs(config);
        assertThat(client.decoder()).isNotNull();
    }

    @Test
    void builderFallsBackToDefaultsIfNothingProvided() {
        SibnetClient client = SibnetClient.builder().build();
        assertThat(client.config().baseUrl()).isEqualTo("https://video.sibnet.ru");
        assertThat(client.decoder()).isNotNull();
    }
}
