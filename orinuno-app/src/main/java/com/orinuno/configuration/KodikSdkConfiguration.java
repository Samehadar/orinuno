package com.orinuno.configuration;

import com.kodik.client.KodikApiClient;
import com.kodik.client.KodikApiRateLimiter;
import com.kodik.client.KodikResponseMapper;
import com.kodik.client.embed.KodikEmbedHttpClient;
import com.kodik.client.http.RotatingUserAgentProvider;
import com.kodik.decoder.KodikDecoderMetrics;
import com.kodik.drift.DriftDetector;
import com.kodik.token.KodikTokenAutoDiscovery;
import com.kodik.token.KodikTokenConfig;
import com.kodik.token.KodikTokenLifecycle;
import com.kodik.token.KodikTokenMetrics;
import com.kodik.token.KodikTokenRegistry;
import com.kodik.token.KodikTokenValidator;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring wiring for the {@code kodik-sdk} module (ADR 0018 Phase 1.2c onwards).
 *
 * <p>The SDK is Spring-free by design — no {@code @Component}, no {@code @Autowired}, no Boot
 * auto-configuration. This class is the single bridge: it translates {@link OrinunoProperties} into
 * plain constructor arguments / config records for SDK types and re-exposes them as Spring beans so
 * the rest of orinuno-app can inject them as before.
 *
 * <p>Symmetric to {@link JutsuSdkConfiguration} for the jut.su SDK and {@link DriftDetectorConfig}
 * for the drift detector. Once {@code kodik-sdk-spring-boot-starter} lands in Phase 1.6, these
 * beans move into the starter and this class shrinks.
 */
@Configuration
public class KodikSdkConfiguration {

    private final OrinunoProperties properties;

    public KodikSdkConfiguration(OrinunoProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RotatingUserAgentProvider rotatingUserAgentProvider() {
        return new RotatingUserAgentProvider();
    }

    @Bean
    public KodikApiRateLimiter kodikApiRateLimiter(OrinunoProperties properties) {
        return new KodikApiRateLimiter(properties.getParse().getRateLimitPerMinute());
    }

    @Bean
    public KodikResponseMapper kodikResponseMapper(DriftDetector driftDetector) {
        return new KodikResponseMapper(driftDetector);
    }

    /**
     * ADR 0018 Phase 1.4b — translates {@link OrinunoProperties.KodikProperties} into the
     * Spring-free {@link KodikTokenConfig} record consumed by the token classes.
     */
    @Bean
    public KodikTokenConfig kodikTokenConfig(OrinunoProperties properties) {
        OrinunoProperties.KodikProperties k = properties.getKodik();
        return KodikTokenConfig.builder()
                .tokenFile(k.getTokenFile())
                .bootstrapToken(k.getToken())
                .bootstrapFromEnv(k.isBootstrapFromEnv())
                .autoDiscoveryEnabled(k.isAutoDiscoveryEnabled())
                .validateOnStartup(k.isValidateOnStartup())
                .deadRevalidationIntervalMinutes(k.getDeadRevalidationIntervalMinutes())
                .tokenFailoverMaxAttempts(k.getTokenFailoverMaxAttempts())
                .build();
    }

    @Bean
    public KodikTokenAutoDiscovery kodikTokenAutoDiscovery(
            WebClient.Builder builder, RotatingUserAgentProvider userAgentProvider) {
        return new KodikTokenAutoDiscovery(builder, userAgentProvider);
    }

    /**
     * Registry is the central piece of the token subsystem. The {@link ObjectProvider}-to-{@link
     * java.util.function.Supplier} adapter preserves the lazy-resolve behaviour that the prior
     * Spring constructor relied on (auto-discovery is only consulted when the on-disk file is
     * absent and bootstrap-from-env yields nothing). {@code init()} replaces the SDK class's former
     * {@code @PostConstruct} and runs after the bean is fully built.
     */
    @Bean
    public KodikTokenRegistry kodikTokenRegistry(
            KodikTokenConfig config,
            ObjectProvider<KodikTokenAutoDiscovery> autoDiscoveryProvider) {
        KodikTokenRegistry registry =
                new KodikTokenRegistry(config, autoDiscoveryProvider::getIfAvailable);
        registry.init();
        return registry;
    }

    @Bean
    public KodikTokenValidator kodikTokenValidator(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            KodikTokenConfig config,
            KodikTokenRegistry registry,
            KodikResponseMapper responseMapper) {
        return new KodikTokenValidator(kodikApiWebClient, config, registry, responseMapper);
    }

    @Bean
    public KodikTokenLifecycle kodikTokenLifecycle(
            KodikTokenValidator validator, KodikTokenConfig config) {
        return new KodikTokenLifecycle(validator, config);
    }

    @Bean
    public KodikTokenMetrics kodikTokenMetrics(
            KodikTokenRegistry registry, MeterRegistry meterRegistry) {
        KodikTokenMetrics metrics = new KodikTokenMetrics(registry);
        metrics.init(meterRegistry);
        return metrics;
    }

    /**
     * ADR 0018 Phase 1.2d — KodikApiClient lifted into kodik-sdk. orinuno-app now hands it the
     * qualified {@code kodikApiWebClient} bean plus the freshly built {@link KodikTokenConfig}; the
     * SDK consumes only {@code tokenFailoverMaxAttempts} from that record at runtime.
     */
    @Bean
    public KodikApiClient kodikApiClient(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            KodikTokenConfig tokenConfig,
            KodikResponseMapper responseMapper,
            KodikApiRateLimiter rateLimiter,
            KodikTokenRegistry tokenRegistry) {
        return new KodikApiClient(
                kodikApiWebClient, tokenConfig, responseMapper, rateLimiter, tokenRegistry);
    }

    /**
     * ADR 0018 Phase 1.3b — decoder metrics live in the SDK so a future standalone
     * orinuno-source-kodik service ships its own Prometheus surface without a hard dependency on
     * orinuno-app's metrics package.
     */
    @Bean
    public KodikDecoderMetrics kodikDecoderMetrics(MeterRegistry meterRegistry) {
        return new KodikDecoderMetrics(meterRegistry);
    }

    @Bean
    public KodikEmbedHttpClient kodikEmbedHttpClient(
            @Qualifier("kodikApiWebClient") WebClient kodikApiWebClient,
            KodikTokenConfig tokenConfig,
            KodikTokenRegistry tokenRegistry,
            KodikApiRateLimiter rateLimiter) {
        return new KodikEmbedHttpClient(kodikApiWebClient, tokenConfig, tokenRegistry, rateLimiter);
    }
}
