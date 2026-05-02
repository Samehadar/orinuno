package com.orinuno.configuration;

import com.orinuno.client.http.RotatingUserAgentProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfiguration {

    @Bean
    public WebClient kodikApiWebClient(
            WebClient.Builder webClientBuilder,
            @Value("${orinuno.kodik.api-url}") String kodikApiUrl) {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                        .build();

        return webClientBuilder.baseUrl(kodikApiUrl).exchangeStrategies(strategies).build();
    }

    @Bean
    public WebClient kodikPlayerWebClient(WebClient.Builder webClientBuilder) {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies =
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                        .build();

        return webClientBuilder.exchangeStrategies(strategies).build();
    }

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
