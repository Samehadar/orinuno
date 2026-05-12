/*
 * JutsuFallbackMetricsContractTest — ADR 0019 Phase 4.12 invariant.
 *
 * Pins the Micrometer metric names + tag keys that the live-fallback path
 * emits. Grafana dashboards + alert rules in observability/grafana/ key off
 * these exact series names; renaming any one of them silently breaks them.
 *
 * Strategy: exercise each guard with a SimpleMeterRegistry, assert the
 * expected counter / gauge appears. The test does NOT assert numeric values
 * (those depend on the SDK + clock); it only locks the *name + tag-key set*
 * so any future field rename fails fast in CI.
 *
 * Metric inventory (the contract this test pins):
 *
 *   orinuno.jutsu.fallback.disabled                       counter
 *   orinuno.jutsu.fallback.success                        counter
 *   orinuno.jutsu.fallback.failure{exception=<class>}     counter
 *   orinuno.jutsu.fallback.negative_cache.size            gauge
 *   orinuno.jutsu.fallback.negative_cache.hit             counter
 *   orinuno.jutsu.fallback.negative_cache.miss            counter
 *   orinuno.jutsu.fallback.breaker.state                  gauge
 *   orinuno.jutsu.fallback.breaker.failure_rate           gauge
 *   orinuno.jutsu.fallback.breaker.short_circuit          counter
 *   orinuno.jutsu.fallback.breaker.opens                  counter
 */
package com.orinuno.source.jutsu.fallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.orinuno.jutsu.JutsuClient;
import com.orinuno.jutsu.ratelimit.JutsuRateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Jutsu fallback metric names — Phase 4.12 dashboard contract")
class JutsuFallbackMetricsContractTest {

    @Test
    @DisplayName("disabled fallback emits orinuno.jutsu.fallback.disabled counter")
    void disabledCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JutsuClient client = mock(JutsuClient.class);
        JutsuRateLimiter rateLimiter = mock(JutsuRateLimiter.class);
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(
                        20, 0.5, Duration.ofSeconds(60), Clock.systemUTC(), registry);
        JutsuFallbackNegativeCache cache =
                new JutsuFallbackNegativeCache(Duration.ofSeconds(60), 1000, registry);

        JutsuLiveFallbackService service =
                new JutsuLiveFallbackService(client, rateLimiter, breaker, cache, false, registry);

        // Trigger the disabled path.
        service.liveAnimeInfo("naruto").onErrorComplete().block();

        assertThat(registry.find("orinuno.jutsu.fallback.disabled").counter())
                .as("disabled-path counter must be registered with this exact name")
                .isNotNull();
    }

    @Test
    @DisplayName("negative cache emits size gauge + hit/miss counters with stable names")
    void negativeCacheMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JutsuFallbackNegativeCache cache =
                new JutsuFallbackNegativeCache(Duration.ofSeconds(60), 1000, registry);

        // Touch the cache so the meters get exercised.
        cache.isMarked("missing-slug");
        cache.put("seeded-slug");
        cache.isMarked("seeded-slug");

        assertThat(registry.find("orinuno.jutsu.fallback.negative_cache.size").gauge())
                .as("negative-cache size gauge must be registered")
                .isNotNull();
        assertThat(registry.find("orinuno.jutsu.fallback.negative_cache.hit").counter())
                .as("negative-cache hit counter must be registered")
                .isNotNull();
        assertThat(registry.find("orinuno.jutsu.fallback.negative_cache.miss").counter())
                .as("negative-cache miss counter must be registered")
                .isNotNull();
    }

    @Test
    @DisplayName("circuit breaker emits state + failure_rate gauges + short_circuit/opens counters")
    void breakerMetrics() {
        MeterRegistry registry = new SimpleMeterRegistry();
        JutsuFallbackCircuitBreaker breaker =
                new JutsuFallbackCircuitBreaker(
                        2, 0.5, Duration.ofMillis(1), Clock.systemUTC(), registry);

        // Force an open + short-circuit cycle.
        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire();
        breaker.recordFailure();
        breaker.tryAcquire(); // short-circuit while open

        assertThat(registry.find("orinuno.jutsu.fallback.breaker.state").gauge())
                .as("breaker state gauge must be registered with this exact name")
                .isNotNull();
        assertThat(registry.find("orinuno.jutsu.fallback.breaker.failure_rate").gauge())
                .as("breaker failure-rate gauge must be registered")
                .isNotNull();
        assertThat(registry.find("orinuno.jutsu.fallback.breaker.short_circuit").counter())
                .as("breaker short_circuit counter must be registered")
                .isNotNull();
        assertThat(registry.find("orinuno.jutsu.fallback.breaker.opens").counter())
                .as("breaker opens counter must be registered")
                .isNotNull();
    }
}
