package com.orinuno.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ParseInboundRateLimiterTest {

    @Test
    @DisplayName("tryConsume succeeds inside the budget and starts the per-consumer counter")
    void successfulConsume() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getParse().getInboundRateLimit().setRequestsPerMinute(3);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ParseInboundRateLimiter limiter = new ParseInboundRateLimiter(properties, registry);

        for (int i = 0; i < 3; i++) {
            ConsumptionProbe probe = limiter.tryConsume("alpha");
            assertThat(probe.isConsumed()).as("attempt %d should succeed", i).isTrue();
        }
    }

    @Test
    @DisplayName("tryConsume rejects after capacity exhausted and bumps Prometheus counter")
    void exhaustedConsume() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getParse().getInboundRateLimit().setRequestsPerMinute(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ParseInboundRateLimiter limiter = new ParseInboundRateLimiter(properties, registry);

        assertThat(limiter.tryConsume("beta").isConsumed()).isTrue();
        ConsumptionProbe second = limiter.tryConsume("beta");
        assertThat(second.isConsumed()).isFalse();
        assertThat(second.getNanosToWaitForRefill()).isPositive();

        Counter counter =
                registry.find("orinuno.inbound.throttle").tag("consumer", "beta").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("buckets are isolated per consumer")
    void perConsumerIsolation() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getParse().getInboundRateLimit().setRequestsPerMinute(1);
        ParseInboundRateLimiter limiter =
                new ParseInboundRateLimiter(properties, new SimpleMeterRegistry());

        assertThat(limiter.tryConsume("a").isConsumed()).isTrue();
        assertThat(limiter.tryConsume("b").isConsumed()).isTrue();
        assertThat(limiter.tryConsume("a").isConsumed()).isFalse();
    }

    @Test
    @DisplayName("requestsPerMinute is clamped to a minimum of 1")
    void minimumOnePerMinute() {
        OrinunoProperties properties = new OrinunoProperties();
        properties.getParse().getInboundRateLimit().setRequestsPerMinute(0);
        ParseInboundRateLimiter limiter =
                new ParseInboundRateLimiter(properties, new SimpleMeterRegistry());

        assertThat(limiter.getRequestsPerMinute()).isEqualTo(1);
        assertThat(limiter.tryConsume("zero").isConsumed()).isTrue();
    }
}
