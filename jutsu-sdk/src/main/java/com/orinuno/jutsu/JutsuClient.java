package com.orinuno.jutsu;

import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.decoder.JutsuDecoder;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Nullable;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Public facade for the JutSu (jut.su) SDK. Wraps the rate limiter, session manager and decoder
 * behind one entry point so callers don't have to know how the parts fit together.
 *
 * <p>Construct via {@link #builder()}:
 *
 * <pre>{@code
 * JutsuClient client = JutsuClient.builder()
 *         .config(JutsuConfig.builder()
 *                 .credentials(System.getenv("JUTSU_USERNAME"),
 *                              System.getenv("JUTSU_PASSWORD"))
 *                 .rateLimitRps(1.0)
 *                 .build())
 *         .build();
 *
 * client.decode("https://jut.su/naruto/episode-1.html")
 *       .subscribe(result -> ...);
 * }</pre>
 *
 * <p>The SDK has no auto-configuration. Spring Boot consumers should wire it from a single
 * {@code @Configuration} class — orinuno-app does this in {@code JutsuSdkConfiguration}.
 */
public final class JutsuClient {

    private final JutsuConfig config;
    private final JutsuRateLimiter rateLimiter;
    private final JutsuSessionManager sessionManager;
    private final JutsuDecoder decoder;

    private JutsuClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            JutsuDecoder decoder) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.sessionManager = sessionManager;
        this.decoder = decoder;
    }

    /**
     * Compose-with-existing-collaborators constructor for callers (typically Spring
     * {@code @Configuration}) that already manage the rate limiter and session manager as
     * singletons and just need the decoder facade on top. The decoder is built fresh because it is
     * stateless; sharing the rate limiter / session manager preserves the invariants those classes
     * rely on (one cookie jar, one bucket).
     */
    public JutsuClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            WebClient.Builder webClientBuilder) {
        this(
                config,
                rateLimiter,
                sessionManager,
                new JutsuDecoder(config, rateLimiter, sessionManager, webClientBuilder));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Decode a single jut.su episode URL into per-quality mp4 links. */
    public Mono<JutsuDecodeResult> decode(String episodeUrl) {
        return decoder.decode(episodeUrl);
    }

    /** Snapshot of the configuration the client was built with. */
    public JutsuConfig config() {
        return config;
    }

    /**
     * The shared rate limiter. Exposed so adjacent components (e.g. a CDN pass-through proxy in
     * orinuno-app) can consume from the same RPS budget rather than spinning up a parallel limiter
     * and silently doubling the outbound rate.
     */
    public JutsuRateLimiter rateLimiter() {
        return rateLimiter;
    }

    /**
     * The shared session manager. Exposed so a CDN proxy can attach the cached cookie header to its
     * own requests — the upstream Yandex CDN URLs require the same session that produced them.
     */
    public JutsuSessionManager sessionManager() {
        return sessionManager;
    }

    /**
     * Builder for {@link JutsuClient}. Pluggable so consumers can wire their own {@link
     * MeterRegistry} or pre-configured {@link WebClient.Builder} (e.g. for Wiremock-driven tests).
     */
    public static final class Builder {
        @Nullable private JutsuConfig config;
        @Nullable private MeterRegistry meterRegistry;
        @Nullable private WebClient.Builder webClientBuilder;

        private Builder() {}

        public Builder config(JutsuConfig config) {
            this.config = config;
            return this;
        }

        /** Optional — defaults to a no-op {@code SimpleMeterRegistry}. */
        public Builder meterRegistry(@Nullable MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        /** Optional — defaults to {@code WebClient.builder()}. */
        public Builder webClientBuilder(@Nullable WebClient.Builder webClientBuilder) {
            this.webClientBuilder = webClientBuilder;
            return this;
        }

        public JutsuClient build() {
            if (config == null) {
                throw new IllegalStateException("config is required — call .config(...) first");
            }
            WebClient.Builder builder =
                    webClientBuilder == null ? WebClient.builder() : webClientBuilder;
            JutsuRateLimiter rateLimiter =
                    new JutsuRateLimiter(config::rateLimitRps, meterRegistry);
            JutsuSessionManager sessionManager =
                    new JutsuSessionManager(config, rateLimiter, builder, meterRegistry);
            JutsuDecoder decoder = new JutsuDecoder(config, rateLimiter, sessionManager, builder);
            return new JutsuClient(config, rateLimiter, sessionManager, decoder);
        }
    }
}
