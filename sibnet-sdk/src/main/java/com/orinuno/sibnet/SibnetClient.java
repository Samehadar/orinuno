package com.orinuno.sibnet;

import com.orinuno.sibnet.decoder.SibnetDecoder;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public facade of the {@code sibnet-sdk}. Stateless wrapper around {@link SibnetDecoder} that lets
 * callers either construct everything from a {@link SibnetConfig} (the typical case — see {@link
 * #builder()}) or share a pre-built decoder (e.g. when several call sites must reuse the same
 * {@code WebClient.Builder}).
 *
 * <pre>{@code
 * SibnetClient client = SibnetClient.builder().build();
 * SibnetDecodeResult result = client.decode("https://video.sibnet.ru/shell.php?videoid=123")
 *     .block();
 * }</pre>
 */
public final class SibnetClient {

    private final SibnetConfig config;
    private final SibnetDecoder decoder;

    public SibnetClient(SibnetConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.decoder = new SibnetDecoder(config, webClientBuilder);
    }

    public SibnetClient(SibnetConfig config, SibnetDecoder decoder) {
        this.config = config;
        this.decoder = decoder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Mono<SibnetDecodeResult> decode(long videoId) {
        return decoder.decode(videoId);
    }

    public Mono<SibnetDecodeResult> decode(String shellUrl) {
        return decoder.decode(shellUrl);
    }

    public SibnetConfig config() {
        return config;
    }

    public SibnetDecoder decoder() {
        return decoder;
    }

    public static final class Builder {
        private SibnetConfig config;
        private WebClient.Builder webClientBuilder;

        private Builder() {}

        public Builder config(SibnetConfig config) {
            this.config = config;
            return this;
        }

        public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        public SibnetClient build() {
            SibnetConfig effectiveConfig = config != null ? config : SibnetConfig.builder().build();
            WebClient.Builder effectiveBuilder =
                    webClientBuilder != null ? webClientBuilder : WebClient.builder();
            return new SibnetClient(effectiveConfig, effectiveBuilder);
        }
    }
}
