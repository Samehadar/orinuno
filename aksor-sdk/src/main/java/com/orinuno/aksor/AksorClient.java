package com.orinuno.aksor;

import com.orinuno.aksor.api.AksorApiClient;
import com.orinuno.aksor.decoder.AksorPipelineDecoder;
import com.orinuno.aksor.host.AksorHostPageParser;
import com.orinuno.aksor.host.AksorHostRegistry;
import com.orinuno.aksor.host.yummy.YummyAniHost;
import com.orinuno.aksor.model.AksorVideoQualities;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public facade of {@code aksor-sdk}.
 *
 * <pre>{@code
 * AksorClient client = AksorClient.builder().build();
 * AksorDecodeResult result =
 *         client.decode("https://old.yummyani.me/catalog/item/monolog-farmatsevta").block();
 * result.value().episodes()
 *         .forEach(ep -> System.out.println(ep.number() + " → " + ep.qualities().bestAvailable()));
 * }</pre>
 *
 * <p>Register a new host site via {@link Builder#registerHost} — no SDK changes needed.
 */
@Slf4j
public final class AksorClient {

    private final AksorConfig config;
    private final AksorHostRegistry hostRegistry;
    private final AksorApiClient apiClient;
    private final AksorPipelineDecoder decoder;

    private AksorClient(
            AksorConfig config,
            AksorHostRegistry hostRegistry,
            AksorApiClient apiClient,
            AksorPipelineDecoder decoder) {
        this.config = config;
        this.hostRegistry = hostRegistry;
        this.apiClient = apiClient;
        this.decoder = decoder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Mono<AksorDecodeResult> decode(String pageUrl) {
        return decoder.decode(pageUrl);
    }

    public Mono<AksorVideoQualities> getQualitiesByHash(String hash) {
        return apiClient.getQualities(hash);
    }

    public Mono<AksorVideoQualities> getQualitiesByHash(String hash, String referer) {
        return apiClient.getQualities(hash, referer);
    }

    public AksorConfig config() {
        return config;
    }

    public AksorHostRegistry hostRegistry() {
        return hostRegistry;
    }

    public AksorApiClient apiClient() {
        return apiClient;
    }

    public AksorPipelineDecoder decoder() {
        return decoder;
    }

    public static final class Builder {
        private AksorConfig config;
        private WebClient.Builder webClientBuilder;
        private final List<AksorHostPageParser> extraHosts = new ArrayList<>();
        private List<AksorHostPageParser> replacementHosts;

        private Builder() {}

        public Builder config(AksorConfig config) {
            this.config = config;
            return this;
        }

        public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        public Builder registerHost(AksorHostPageParser host) {
            if (host == null) {
                throw new IllegalArgumentException("host must not be null");
            }
            this.extraHosts.add(host);
            return this;
        }

        public Builder replaceHosts(List<AksorHostPageParser> hosts) {
            if (hosts == null || hosts.isEmpty()) {
                throw new IllegalArgumentException("replacement hosts must be non-empty");
            }
            this.replacementHosts = List.copyOf(hosts);
            return this;
        }

        public AksorClient build() {
            AksorConfig effectiveConfig = config != null ? config : AksorConfig.builder().build();
            WebClient.Builder builder =
                    webClientBuilder != null ? webClientBuilder : WebClient.builder();
            AksorApiClient apiClient = new AksorApiClient(effectiveConfig, builder);

            List<AksorHostPageParser> hosts;
            if (replacementHosts != null) {
                hosts = replacementHosts;
            } else {
                hosts = new ArrayList<>();
                hosts.add(new YummyAniHost(effectiveConfig, builder));
                hosts.addAll(extraHosts);
            }
            AksorHostRegistry registry = new AksorHostRegistry(hosts);
            AksorPipelineDecoder decoder =
                    new AksorPipelineDecoder(effectiveConfig, registry, apiClient);
            return new AksorClient(effectiveConfig, registry, apiClient, decoder);
        }
    }
}
