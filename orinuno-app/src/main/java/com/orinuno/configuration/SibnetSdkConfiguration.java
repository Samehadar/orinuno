package com.orinuno.configuration;

import com.kodik.client.http.RotatingUserAgentProvider;
import com.orinuno.sibnet.SibnetClient;
import com.orinuno.sibnet.SibnetConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires the standalone {@code sibnet-sdk} ({@link SibnetClient}) into the Spring context. We thread
 * the existing {@link RotatingUserAgentProvider} into {@link SibnetConfig} so the SDK uses the
 * exact same UA orinuno-app already sends to other providers — easier to debug and one less knob
 * for operators to remember.
 *
 * <p>{@link SibnetClient} is a singleton; everything inside it is stateless, so reusing the
 * underlying {@code WebClient} across all call sites is safe and avoids one Netty connection pool
 * per request.
 */
@Configuration
public class SibnetSdkConfiguration {

    @Bean
    public SibnetConfig sibnetConfig(RotatingUserAgentProvider userAgents) {
        return SibnetConfig.builder().userAgent(userAgents.stableDesktop()).build();
    }

    @Bean
    public SibnetClient sibnetClient(SibnetConfig config, WebClient.Builder webClientBuilder) {
        return new SibnetClient(config, webClientBuilder);
    }
}
