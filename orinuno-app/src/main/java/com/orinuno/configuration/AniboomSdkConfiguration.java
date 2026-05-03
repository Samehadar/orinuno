package com.orinuno.configuration;

import com.orinuno.aniboom.AniboomClient;
import com.orinuno.aniboom.AniboomConfig;
import com.orinuno.client.http.RotatingUserAgentProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the standalone {@code aniboom-sdk} ({@link AniboomClient}) into the Spring context. Same
 * shape as {@link SibnetSdkConfiguration}: pull the UA from {@link RotatingUserAgentProvider} for
 * consistency, share one Netty pool across the app.
 */
@Configuration
public class AniboomSdkConfiguration {

    @Bean
    public AniboomConfig aniboomConfig(RotatingUserAgentProvider userAgents) {
        return AniboomConfig.builder().userAgent(userAgents.stableDesktop()).build();
    }

    @Bean
    public AniboomClient aniboomClient(AniboomConfig config, WebClient.Builder webClientBuilder) {
        return new AniboomClient(config, webClientBuilder);
    }
}
