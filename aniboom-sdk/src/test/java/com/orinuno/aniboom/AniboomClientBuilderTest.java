package com.orinuno.aniboom;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AniboomClientBuilderTest {

    @Test
    void builderProducesConfiguredClient() {
        AniboomConfig config = AniboomConfig.builder().userAgent("test/1.0").build();
        AniboomClient client =
                AniboomClient.builder()
                        .config(config)
                        .webClientBuilder(WebClient.builder())
                        .build();
        assertThat(client.config()).isSameAs(config);
        assertThat(client.decoder()).isNotNull();
    }

    @Test
    void builderFallsBackToDefaultsIfNothingProvided() {
        AniboomClient client = AniboomClient.builder().build();
        assertThat(client.config().baseUrl()).isEqualTo("https://aniboom.one");
        assertThat(client.decoder()).isNotNull();
    }
}
