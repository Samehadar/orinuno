package com.kodik.sdk.spring;

import com.kodik.client.KodikApiClient;
import com.kodik.client.KodikApiRateLimiter;
import com.kodik.client.KodikResponseMapper;
import com.kodik.client.embed.KodikEmbedHttpClient;
import com.kodik.client.http.RotatingUserAgentProvider;
import com.kodik.decoder.KodikDecoderMetrics;
import com.kodik.drift.DriftDetector;
import com.kodik.drift.DriftSamplingProperties;
import com.kodik.token.KodikTokenAutoDiscovery;
import com.kodik.token.KodikTokenConfig;
import com.kodik.token.KodikTokenLifecycle;
import com.kodik.token.KodikTokenMetrics;
import com.kodik.token.KodikTokenRegistry;
import com.kodik.token.KodikTokenValidator;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Out-of-the-box Spring Boot wiring for kodik-sdk (ADR 0018 Phase 1.6).
 *
 * <p>Every bean is gated by {@code @ConditionalOnMissingBean} so a host application that ships its
 * own {@code @Configuration} (orinuno-app's KodikSdkConfiguration) keeps total control; the
 * auto-config only fills in beans the host left unset. Intended for orinuno-source-kodik (Phase 2)
 * and OSS consumers that do not want to hand-roll the @Bean wiring.
 *
 * <p>Requires a {@code WebClient} bean named {@code kodikApiWebClient} on the classpath — the host
 * must provide the HTTP transport (base URL, SSL, proxy). KodikTokenAutoDiscovery also needs a
 * {@code WebClient.Builder} bean, which Spring Boot's WebFlux auto-configuration supplies by
 * default.
 *
 * <p>Bind via {@code kodik.sdk.*} property prefix; see {@link KodikSdkProperties}.
 */
@AutoConfiguration
@EnableConfigurationProperties(KodikSdkProperties.class)
public class KodikSdkAutoConfiguration {

    public KodikSdkAutoConfiguration() {}

    @Bean
    @ConditionalOnMissingBean
    public RotatingUserAgentProvider rotatingUserAgentProvider() {
        return new RotatingUserAgentProvider();
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikApiRateLimiter kodikApiRateLimiter(KodikSdkProperties properties) {
        return new KodikApiRateLimiter(properties.getRateLimitPerMinute());
    }

    @Bean
    @ConditionalOnMissingBean
    public DriftDetector driftDetector() {
        return new DriftDetector(DriftSamplingProperties.defaults());
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikResponseMapper kodikResponseMapper(DriftDetector driftDetector) {
        return new KodikResponseMapper(driftDetector);
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikTokenConfig kodikTokenConfig(KodikSdkProperties properties) {
        return properties.toTokenConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikTokenAutoDiscovery kodikTokenAutoDiscovery(
            WebClient.Builder builder, RotatingUserAgentProvider userAgentProvider) {
        return new KodikTokenAutoDiscovery(builder, userAgentProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikTokenRegistry kodikTokenRegistry(
            KodikTokenConfig config,
            ObjectProvider<KodikTokenAutoDiscovery> autoDiscoveryProvider) {
        KodikTokenRegistry registry =
                new KodikTokenRegistry(config, autoDiscoveryProvider::getIfAvailable);
        registry.init();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikTokenValidator kodikTokenValidator(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            KodikTokenConfig config,
            KodikTokenRegistry registry,
            KodikResponseMapper responseMapper) {
        return new KodikTokenValidator(kodikApiWebClient, config, registry, responseMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikTokenLifecycle kodikTokenLifecycle(
            KodikTokenValidator validator, KodikTokenConfig config) {
        return new KodikTokenLifecycle(validator, config);
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikTokenMetrics kodikTokenMetrics(
            KodikTokenRegistry registry, MeterRegistry meterRegistry) {
        KodikTokenMetrics metrics = new KodikTokenMetrics(registry);
        metrics.init(meterRegistry);
        return metrics;
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikDecoderMetrics kodikDecoderMetrics(MeterRegistry meterRegistry) {
        return new KodikDecoderMetrics(meterRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikApiClient kodikApiClient(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            KodikTokenConfig tokenConfig,
            KodikResponseMapper responseMapper,
            KodikApiRateLimiter rateLimiter,
            KodikTokenRegistry tokenRegistry) {
        return new KodikApiClient(
                kodikApiWebClient, tokenConfig, responseMapper, rateLimiter, tokenRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public KodikEmbedHttpClient kodikEmbedHttpClient(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            KodikTokenConfig tokenConfig,
            KodikTokenRegistry tokenRegistry,
            KodikApiRateLimiter rateLimiter) {
        return new KodikEmbedHttpClient(kodikApiWebClient, tokenConfig, tokenRegistry, rateLimiter);
    }

    /**
     * Lifecycle runner — separate @Component so the startup + scheduled hooks don't create a
     * self-referencing cycle on this @AutoConfiguration (Spring 6.2 prohibits ctor injection of a
     * same-class @Bean even via ObjectProvider). Mirrors orinuno-app's KodikTokenLifecycleRunner.
     */
    @Component
    public static class LifecycleRunner {
        private final KodikTokenLifecycle lifecycle;

        public LifecycleRunner(KodikTokenLifecycle lifecycle) {
            this.lifecycle = lifecycle;
        }

        @PostConstruct
        public void onStart() {
            lifecycle.onStart();
        }

        @Scheduled(
                fixedRateString = "${orinuno.kodik.validation-interval-minutes:360}",
                timeUnit = java.util.concurrent.TimeUnit.MINUTES,
                initialDelayString = "${orinuno.kodik.validation-interval-minutes:360}")
        public void scheduledTokenRevalidation() {
            lifecycle.scheduledRevalidation();
        }
    }
}
