package com.orinuno.aniboom;

import com.orinuno.aniboom.decoder.AniboomDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public facade of the {@code aniboom-sdk}. Stateless wrapper around {@link AniboomDecoder} that
 * lets callers either construct everything from a {@link AniboomConfig} (the typical case — see
 * {@link #builder()}) or share a pre-built decoder.
 *
 * <pre>{@code
 * AniboomClient client = AniboomClient.builder().build();
 * AniboomDecodeResult result = client.decode("https://aniboom.one/embed/abc123").block();
 * }</pre>
 */
public final class AniboomClient {

    private final AniboomConfig config;
    private final AniboomDecoder decoder;

    public AniboomClient(AniboomConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.decoder = new AniboomDecoder(config, webClientBuilder);
    }

    public AniboomClient(AniboomConfig config, AniboomDecoder decoder) {
        this.config = config;
        this.decoder = decoder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Mono<AniboomDecodeResult> decode(String embedUrl) {
        return decoder.decode(embedUrl);
    }

    public AniboomConfig config() {
        return config;
    }

    public AniboomDecoder decoder() {
        return decoder;
    }

    public static final class Builder {
        private AniboomConfig config;
        private WebClient.Builder webClientBuilder;

        private Builder() {}

        public Builder config(AniboomConfig config) {
            this.config = config;
            return this;
        }

        public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        public AniboomClient build() {
            AniboomConfig effectiveConfig =
                    config != null ? config : AniboomConfig.builder().build();
            WebClient.Builder effectiveBuilder =
                    webClientBuilder != null ? webClientBuilder : WebClient.builder();
            return new AniboomClient(effectiveConfig, effectiveBuilder);
        }
    }
}
