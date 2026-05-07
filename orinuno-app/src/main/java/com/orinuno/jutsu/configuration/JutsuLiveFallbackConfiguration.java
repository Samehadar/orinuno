package com.orinuno.jutsu.configuration;

import com.orinuno.configuration.JutsuLiveFallbackProperties;
import com.orinuno.jutsu.fallback.JutsuLiveFallbackService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the jut.su live-fallback subsystem (ADR 0016 P1a §"REST cutover for jut.su").
 * Kept separate from {@link JutsuCatalogSyncConfiguration} so the two subsystems have independent
 * lifecycles — sync workers are background ticks, live-fallback is request-time DDoS protection.
 */
@Configuration
public class JutsuLiveFallbackConfiguration {

    @Bean
    public JutsuLiveFallbackService jutsuLiveFallbackService(
            JutsuLiveFallbackProperties properties, MeterRegistry meterRegistry) {
        return new JutsuLiveFallbackService(properties, meterRegistry);
    }
}
