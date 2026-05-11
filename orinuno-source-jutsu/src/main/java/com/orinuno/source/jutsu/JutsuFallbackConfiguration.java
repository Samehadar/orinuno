/*
 * JutsuFallbackConfiguration — live-fallback Spring wiring (ADR 0019 Phase 4.7).
 *
 * Three guards + the orchestrator service. Knobs read from
 * JutsuSourceProperties.fallback. The jutsuFallbackRateLimiter is a SECOND
 * JutsuRateLimiter bean with its own bucket — distinct from the SDK's main
 * bucket the schedulers share. Per ADR 0016, a flood of cache-miss fallbacks
 * must not starve the sync worker and vice-versa.
 *
 * No Playwright dependency here despite ADR 0019 §"Playwright live-fallback":
 * the actual implementation is pure SDK reactive client; the "Playwright"
 * label is historical, kept for compatibility with the original PR text.
 */
package com.orinuno.source.jutsu;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import com.orinuno.source.jutsu.fallback.JutsuFallbackCircuitBreaker;
import com.orinuno.source.jutsu.fallback.JutsuFallbackNegativeCache;
import com.orinuno.source.jutsu.fallback.JutsuLiveFallbackService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JutsuFallbackConfiguration {

    @Bean(name = "jutsuFallbackRateLimiter")
    public JutsuRateLimiter jutsuFallbackRateLimiter(
            JutsuSourceProperties props, MeterRegistry meterRegistry) {
        return new JutsuRateLimiter(() -> props.getFallback().getRateLimitRps(), meterRegistry);
    }

    @Bean
    public JutsuFallbackCircuitBreaker jutsuFallbackCircuitBreaker(
            JutsuSourceProperties props, MeterRegistry meterRegistry) {
        JutsuSourceProperties.FallbackProperties.CircuitBreakerProperties cb =
                props.getFallback().getCircuitBreaker();
        return new JutsuFallbackCircuitBreaker(
                cb.getWindowSize(),
                cb.getFailureRateThreshold(),
                Duration.ofSeconds(cb.getOpenPauseSeconds()),
                Clock.systemUTC(),
                meterRegistry);
    }

    @Bean
    public JutsuFallbackNegativeCache jutsuFallbackNegativeCache(
            JutsuSourceProperties props, MeterRegistry meterRegistry) {
        JutsuSourceProperties.FallbackProperties.NegativeCacheProperties nc =
                props.getFallback().getNegativeCache();
        return new JutsuFallbackNegativeCache(
                Duration.ofSeconds(nc.getTtlSeconds()), nc.getMaxSize(), meterRegistry);
    }

    @Bean
    public JutsuLiveFallbackService jutsuLiveFallbackService(
            JutsuClient client,
            @Qualifier("jutsuFallbackRateLimiter") JutsuRateLimiter fallbackRateLimiter,
            JutsuFallbackCircuitBreaker breaker,
            JutsuFallbackNegativeCache negativeCache,
            JutsuSourceProperties props,
            MeterRegistry meterRegistry) {
        return new JutsuLiveFallbackService(
                client,
                fallbackRateLimiter,
                breaker,
                negativeCache,
                props.getFallback().isEnabled(),
                meterRegistry);
    }
}
