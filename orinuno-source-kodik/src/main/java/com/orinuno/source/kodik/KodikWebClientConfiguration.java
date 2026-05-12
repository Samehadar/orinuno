/*
 * KodikWebClientConfiguration — provides the kodikApiWebClient bean that the kodik-sdk
 * auto-config requires. orinuno-source-kodik owns its own HTTP transport (base URL, codec
 * limits, future proxy hook) — mirrors orinuno-app's WebClientConfiguration but limited to
 * the Kodik API client (player/CDN clients are decoder concerns and land alongside the
 * decoder service migration in Phase 2.x).
 */
package com.orinuno.source.kodik;

import com.kodik.client.http.RotatingUserAgentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

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

    /**
     * ADR 0021 §D1b — kodik player JS fetcher. Lifted from orinuno-app's WebClientConfiguration
     * unchanged. Required by KodikVideoDecoderService when it lands in D1b-2.
     */
    @Bean
    public WebClient kodikPlayerWebClient(WebClient.Builder webClientBuilder) {
        ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                        .build();
        return webClientBuilder.exchangeStrategies(strategies).build();
    }

    /**
     * ADR 0021 §D1b — CDN HEAD/GET proxy client (no auto-redirect so the upstream Location header
     * surfaces to StreamController. Used by both the decoder + StreamController/HlsController
     * stack once they land in D1b-2 + C2.
     */
    @Bean
    public WebClient kodikCdnWebClient(RotatingUserAgentProvider userAgentProvider) {
        HttpClient httpClient = HttpClient.create().followRedirect(false);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Referer", "https://kodikplayer.com/")
                .defaultHeader("User-Agent", userAgentProvider.stableDesktop())
                .build();
    }
}
