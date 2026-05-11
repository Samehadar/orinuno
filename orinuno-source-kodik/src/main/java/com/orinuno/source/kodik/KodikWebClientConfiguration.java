/*
 * KodikWebClientConfiguration — provides the kodikApiWebClient bean that the kodik-sdk
 * auto-config requires. orinuno-source-kodik owns its own HTTP transport (base URL, codec
 * limits, future proxy hook) — mirrors orinuno-app's WebClientConfiguration but limited to
 * the Kodik API client (player/CDN clients are decoder concerns and land alongside the
 * decoder service migration in Phase 2.x).
 */
package com.orinuno.source.kodik;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Provides the qualified {@code kodikApiWebClient} bean consumed by KodikApiClient /
 * KodikEmbedHttpClient / KodikTokenValidator inside kodik-sdk-spring-boot-starter.
 *
 * <p>Codec budget set to 16 MiB to accommodate Kodik {@code /list} responses that exceed Spring's
 * default 256 KiB cap. Base URL is sourced from {@code kodik.api-url} (default production endpoint)
 * so OSS deployers can point at a local mock for development.
 */
@Configuration
public class KodikWebClientConfiguration {

    @Bean
    public WebClient kodikApiWebClient(
            WebClient.Builder webClientBuilder,
            @Value("${kodik.api-url:https://kodik-api.com}") String kodikApiUrl) {
        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        return webClientBuilder.baseUrl(kodikApiUrl).exchangeStrategies(strategies).build();
    }
}
