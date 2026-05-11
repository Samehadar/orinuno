/*
 * JutsuSdkConfiguration — Spring wiring for jutsu-sdk inside orinuno-source-jutsu (ADR 0019 Phase 4.4).
 *
 * Mirrors orinuno-app's JutsuSdkConfiguration but reads from this service's
 * JutsuSourceProperties instead of OrinunoProperties. The SDK has zero Spring
 * auto-configuration today (ADR 0019 §"Reactor changes" — no
 * jutsu-sdk-spring-boot-starter yet), so this @Configuration is the single
 * bridge between properties and SDK beans.
 *
 * UserAgent: the orinuno-app version pulls RotatingUserAgentProvider out of
 * kodik-sdk's RotatingUserAgentProvider; here we inline a stable desktop UA
 * string instead — jut.su doesn't need the rotating-Kodik path and we keep
 * this service Kodik-free at the dependency level.
 */
package com.orinuno.source.jutsu;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.JutsuConfig;
import com.orinuno.jutsu.auth.JutsuSessionManager;
import com.orinuno.jutsu.drift.JutsuDriftDetector;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@EnableConfigurationProperties(JutsuSourceProperties.class)
public class JutsuSdkConfiguration {

    /** Stable desktop UA — keeps requests indistinguishable from a normal Chrome on Linux. */
    private static final String STABLE_DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Chrome/120.0.0.0 Safari/537.36";

    @Bean
    public JutsuConfig jutsuConfig(JutsuSourceProperties props) {
        return JutsuConfig.builder()
                .baseUrl(props.getBaseUrl())
                .credentials(props.getUsername(), props.getPassword())
                .userAgent(STABLE_DESKTOP_UA)
                .rateLimitRps(props.getRateLimitRps())
                .sessionTtl(Duration.ofMinutes(Math.max(1, props.getSessionTtlMinutes())))
                .loginTimeout(Duration.ofSeconds(Math.max(1, props.getLoginTimeoutSeconds())))
                .build();
    }

    @Bean
    @Primary
    public JutsuRateLimiter jutsuRateLimiter(
            JutsuSourceProperties props, MeterRegistry meterRegistry) {
        return new JutsuRateLimiter(props::getRateLimitRps, meterRegistry);
    }

    @Bean
    public JutsuSessionManager jutsuSessionManager(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            WebClient.Builder webClientBuilder,
            MeterRegistry meterRegistry) {
        return new JutsuSessionManager(config, rateLimiter, webClientBuilder, meterRegistry);
    }

    @Bean
    public JutsuDriftDetector jutsuDriftDetector() {
        return new JutsuDriftDetector();
    }

    @Bean
    public JutsuClient jutsuClient(
            JutsuConfig config,
            JutsuRateLimiter rateLimiter,
            JutsuSessionManager sessionManager,
            JutsuDriftDetector driftDetector,
            MeterRegistry meterRegistry,
            WebClient.Builder webClientBuilder) {
        return JutsuClient.builder()
                .config(config)
                .rateLimiter(rateLimiter)
                .sessionManager(sessionManager)
                .driftDetector(driftDetector)
                .meterRegistry(meterRegistry)
                .webClientBuilder(webClientBuilder)
                .build();
    }
}
