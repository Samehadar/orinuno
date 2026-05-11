package com.orinuno.cvh;

import com.orinuno.cvh.api.CvhApiClient;
import com.orinuno.cvh.api.CvhVideoSourcesCache;
import com.orinuno.cvh.decoder.CvhPipelineDecoder;
import com.orinuno.cvh.host.CvhHostPageParser;
import com.orinuno.cvh.host.CvhHostRegistry;
import com.orinuno.cvh.host.jutsu.JutsuCvhHost;
import com.orinuno.cvh.model.CvhVideoSources;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public facade of the {@code cvh-sdk}. Stateless apart from {@link CvhVideoSourcesCache}; wires a
 * {@link CvhHostRegistry} (default contains {@link JutsuCvhHost}), a {@link CvhApiClient}, and the
 * pipeline decoder.
 *
 * <pre>{@code
 * CvhClient client = CvhClient.builder().build();
 * CvhDecodeResult result = client.decode("https://jut-su.works/<slug>").block();
 * if (result.success()) {
 *     result.value().tracks().forEach(t -> System.out.println(t.sources().hlsUrl()));
 * }
 * }</pre>
 *
 * <p>Register a new host site via {@link Builder#registerHost} — no SDK changes needed.
 */
public final class CvhClient {

    private final CvhConfig config;
    private final CvhHostRegistry hostRegistry;
    private final CvhApiClient apiClient;
    private final CvhVideoSourcesCache cache;
    private final CvhPipelineDecoder decoder;

    private CvhClient(
            CvhConfig config,
            CvhHostRegistry hostRegistry,
            CvhApiClient apiClient,
            CvhVideoSourcesCache cache,
            CvhPipelineDecoder decoder) {
        this.config = config;
        this.hostRegistry = hostRegistry;
        this.apiClient = apiClient;
        this.cache = cache;
        this.decoder = decoder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Mono<CvhDecodeResult> decode(String pageUrl) {
        return decoder.decode(pageUrl);
    }

    public Mono<CvhVideoSources> getSourcesByVkId(String vkId) {
        return decoder.getSourcesByVkId(vkId);
    }

    /**
     * Fast-path with explicit referer override. Use this when calling outside a {@link
     * #decode(String)} flow — CVH plapi gates access by publisher-whitelisted referer, so the
     * caller must supply one that matches the host page (e.g. {@code https://jut-su.works/}).
     */
    public Mono<CvhVideoSources> getSourcesByVkId(String vkId, String referer) {
        return decoder.getSourcesByVkId(vkId, referer);
    }

    public CvhConfig config() {
        return config;
    }

    public CvhHostRegistry hostRegistry() {
        return hostRegistry;
    }

    public CvhApiClient apiClient() {
        return apiClient;
    }

    public CvhVideoSourcesCache cache() {
        return cache;
    }

    public CvhPipelineDecoder decoder() {
        return decoder;
    }

    public static final class Builder {
        private CvhConfig config;
        private WebClient.Builder webClientBuilder;
        private final List<CvhHostPageParser> extraHosts = new ArrayList<>();
        private List<CvhHostPageParser> replacementHosts;

        private Builder() {}

        public Builder config(CvhConfig config) {
            this.config = config;
            return this;
        }

        public Builder webClientBuilder(WebClient.Builder webClientBuilder) {
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        /** Add a host parser on top of the default {@link JutsuCvhHost}. */
        public Builder registerHost(CvhHostPageParser host) {
            if (host == null) {
                throw new IllegalArgumentException("host must not be null");
            }
            this.extraHosts.add(host);
            return this;
        }

        /** Replace the entire host registry (drops the default {@link JutsuCvhHost}). */
        public Builder replaceHosts(List<CvhHostPageParser> hosts) {
            if (hosts == null || hosts.isEmpty()) {
                throw new IllegalArgumentException("replacement hosts must be non-empty");
            }
            this.replacementHosts = List.copyOf(hosts);
            return this;
        }

        public CvhClient build() {
            CvhConfig effectiveConfig = config != null ? config : CvhConfig.builder().build();
            WebClient.Builder effectiveBuilder =
                    webClientBuilder != null ? webClientBuilder : WebClient.builder();

            List<CvhHostPageParser> hosts;
            if (replacementHosts != null) {
                hosts = replacementHosts;
            } else {
                hosts = new ArrayList<>();
                hosts.add(new JutsuCvhHost(effectiveConfig.defaultAggregator()));
                hosts.addAll(extraHosts);
            }
            CvhHostRegistry registry = new CvhHostRegistry(hosts);
            CvhApiClient apiClient = new CvhApiClient(effectiveConfig, effectiveBuilder);
            CvhVideoSourcesCache cache = new CvhVideoSourcesCache(apiClient, effectiveConfig);
            CvhPipelineDecoder decoder =
                    new CvhPipelineDecoder(
                            effectiveConfig, registry, apiClient, cache, effectiveBuilder);
            return new CvhClient(effectiveConfig, registry, apiClient, cache, decoder);
        }
    }
}
