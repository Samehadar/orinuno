package com.orinuno.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientConfiguration {

    private static final String CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36";

    @Bean
    public WebClient kodikApiWebClient(
            WebClient.Builder webClientBuilder,
            @Value("${orinuno.kodik.api-url}") String kodikApiUrl
    ) {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();

        return webClientBuilder
                .baseUrl(kodikApiUrl)
                .exchangeStrategies(strategies)
                .build();
    }

    @Bean
    public WebClient kodikPlayerWebClient(WebClient.Builder webClientBuilder) {
        final int size = 16 * 1024 * 1024;
        final ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(size))
                .build();

        return webClientBuilder
                .exchangeStrategies(strategies)
                .build();
    }

    @Bean
    public WebClient kodikCdnWebClient() {
        HttpClient httpClient = HttpClient.create()
                .followRedirect(false);

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("Referer", "https://kodikplayer.com/")
                .defaultHeader("User-Agent", CHROME_UA)
                .build();
    }
}
