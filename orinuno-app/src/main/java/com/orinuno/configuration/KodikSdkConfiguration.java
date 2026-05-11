package com.orinuno.configuration;

import com.kodik.client.KodikApiRateLimiter;
import com.kodik.client.KodikResponseMapper;
import com.kodik.client.http.RotatingUserAgentProvider;
import com.kodik.sdk.drift.DriftDetector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the {@code kodik-sdk} module (ADR 0018 Phase 1.2c onwards).
 *
 * <p>The SDK is Spring-free by design — no {@code @Component}, no {@code @Autowired}, no Boot
 * auto-configuration. This class is the single bridge: it translates {@link OrinunoProperties} into
 * plain constructor arguments for SDK types and re-exposes them as Spring beans so the rest of
 * orinuno-app (controllers, services, tests) can inject them as before — only the package import
 * changes.
 *
 * <p>Symmetric to {@link JutsuSdkConfiguration} for the jut.su SDK and {@link DriftDetectorConfig}
 * for the drift detector. Once {@code kodik-sdk-spring-boot-starter} lands in Phase 1.6, these
 * beans move into the starter and this configuration class shrinks.
 */
@Configuration
public class KodikSdkConfiguration {

    /**
     * Centralises the desktop User-Agent pool used by every Kodik HTTP path (iframe HTML, player
     * JS, decoder POST, CDN HLS fetch, Playwright stealth context). The provider is stateless and
     * thread-safe — a single bean is shared across the app.
     */
    @Bean
    public RotatingUserAgentProvider rotatingUserAgentProvider() {
        return new RotatingUserAgentProvider();
    }

    /**
     * Outbound rate budget for every call into Kodik's REST API. The SDK takes a plain {@code int}
     * for the per-minute permit count so it has no compile-time link to {@link OrinunoProperties};
     * we extract the value here and hand it over.
     */
    @Bean
    public KodikApiRateLimiter kodikApiRateLimiter(OrinunoProperties properties) {
        return new KodikApiRateLimiter(properties.getParse().getRateLimitPerMinute());
    }

    /**
     * Jackson-based deserialiser for Kodik raw responses, wrapping the {@link DriftDetector}. The
     * two-arg constructor is the Spring-side one; the no-arg constructor (default drift detector)
     * is used only by plain unit tests.
     */
    @Bean
    public KodikResponseMapper kodikResponseMapper(DriftDetector driftDetector) {
        return new KodikResponseMapper(driftDetector);
    }
}
