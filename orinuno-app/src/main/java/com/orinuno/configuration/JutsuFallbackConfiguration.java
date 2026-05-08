package com.orinuno.configuration;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.fallback.JutsuFallbackCircuitBreaker;
import com.orinuno.jutsu.fallback.JutsuFallbackNegativeCache;
import com.orinuno.jutsu.fallback.JutsuLiveFallbackService;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the live-fallback path (ARCH-0016 P1a Step 3.B). Three guard beans + the
 * orchestrator service. All knobs are read from {@link OrinunoProperties} so a hot reload of {@code
 * orinuno.providers.jutsu.fallback.*} takes effect on the next bean rebuild — at the moment that
 * means a restart, since none of these beans implement runtime reload (the breaker window size etc.
 * are immutable after construction). This is fine for the current scale; a future iteration can
 * switch them to suppliers if we need live tuning.
 *
 * <p>The {@code jutsuFallbackRateLimiter} bean is a SECOND instance of {@link JutsuRateLimiter}
 * with its own bucket — distinct from the SDK's main bucket the sync workers share. That's the
 * "separate bucket" decision documented in ADR 0016 / Step 3 plan: a flood of cache-miss fallbacks
 * must not starve the sync worker and vice-versa.
 */
@Configuration
public class JutsuFallbackConfiguration {

    /**
     * Dedicated rate-limit bucket for live fallback. Default 0.5 RPS (one request every 2s) — tuned
     * to be slow enough that a runaway cache-miss flood can't drown jut.su, fast enough that
     * legitimate bursts (a single human exploring the catalog) don't feel laggy.
     */
    @Bean(name = "jutsuFallbackRateLimiter")
    public JutsuRateLimiter jutsuFallbackRateLimiter(
            OrinunoProperties properties, MeterRegistry meterRegistry) {
        return new JutsuRateLimiter(
                () -> properties.getProviders().getJutsu().getFallback().getRateLimitRps(),
                meterRegistry);
    }

    @Bean
    public JutsuFallbackCircuitBreaker jutsuFallbackCircuitBreaker(
            OrinunoProperties properties, MeterRegistry meterRegistry) {
        OrinunoProperties.JutsuProperties.FallbackProperties.CircuitBreakerProperties cb =
                properties.getProviders().getJutsu().getFallback().getCircuitBreaker();
        return new JutsuFallbackCircuitBreaker(
                cb.getWindowSize(),
                cb.getFailureRateThreshold(),
                Duration.ofSeconds(cb.getOpenPauseSeconds()),
                Clock.systemUTC(),
                meterRegistry);
    }

    @Bean
    public JutsuFallbackNegativeCache jutsuFallbackNegativeCache(
            OrinunoProperties properties, MeterRegistry meterRegistry) {
        OrinunoProperties.JutsuProperties.FallbackProperties.NegativeCacheProperties nc =
                properties.getProviders().getJutsu().getFallback().getNegativeCache();
        return new JutsuFallbackNegativeCache(
                Duration.ofSeconds(nc.getTtlSeconds()), nc.getMaxSize(), meterRegistry);
    }

    @Bean
    public JutsuLiveFallbackService jutsuLiveFallbackService(
            JutsuClient client,
            @Qualifier("jutsuFallbackRateLimiter") JutsuRateLimiter fallbackRateLimiter,
            JutsuFallbackCircuitBreaker breaker,
            JutsuFallbackNegativeCache negativeCache,
            OrinunoProperties properties,
            MeterRegistry meterRegistry) {
        return new JutsuLiveFallbackService(
                client,
                fallbackRateLimiter,
                breaker,
                negativeCache,
                properties.getProviders().getJutsu().getFallback().isEnabled(),
                meterRegistry);
    }
}
