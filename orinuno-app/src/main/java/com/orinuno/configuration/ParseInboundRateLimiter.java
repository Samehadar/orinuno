package com.orinuno.configuration;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Per-consumer (X-Created-By) inbound rate limiter for {@code POST /api/v1/parse/requests}.
 *
 * <p>One Bucket4j token bucket per distinct consumer key, refilled greedily over a 1-minute window.
 * Both the configured {@code requestsPerMinute} budget and exhaustion events are exposed via
 * Prometheus ({@code orinuno_inbound_throttle_total{consumer=…}}).
 *
 * <p>This is single-instance only: every replica owns its own buckets. Consumers running against a
 * replicated deployment will see effectively N×budget — see operations/parser-kodik-integration §6.
 */
@Slf4j
@Component
public class ParseInboundRateLimiter {

    private final OrinunoProperties properties;
    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> throttleCounters = new ConcurrentHashMap<>();

    public ParseInboundRateLimiter(OrinunoProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public boolean isEnabled() {
        return properties.getParse().getInboundRateLimit().isEnabled();
    }

    public int getRequestsPerMinute() {
        return Math.max(1, properties.getParse().getInboundRateLimit().getRequestsPerMinute());
    }

    /**
     * Try to consume a single token for the given consumer. Returns a probe describing whether the
     * call is allowed and how many nanoseconds until the next refill if not.
     */
    public ConsumptionProbe tryConsume(String consumer) {
        Bucket bucket = buckets.computeIfAbsent(consumer, this::newBucket);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            throttleCounter(consumer).increment();
            log.warn(
                    "🚦 Inbound rate-limit hit for consumer={} (budget={}/min, retryAfter={}ms)",
                    sanitizeForLog(consumer),
                    getRequestsPerMinute(),
                    Duration.ofNanos(probe.getNanosToWaitForRefill()).toMillis());
        }
        return probe;
    }

    private Bucket newBucket(String consumer) {
        Bandwidth limit =
                Bandwidth.builder()
                        .capacity(getRequestsPerMinute())
                        .refillGreedy(getRequestsPerMinute(), Duration.ofMinutes(1))
                        .build();
        log.debug(
                "🪣 Created inbound bucket consumer={} capacity={}/min",
                sanitizeForLog(consumer),
                getRequestsPerMinute());
        return Bucket.builder().addLimit(limit).build();
    }

    private Counter throttleCounter(String consumer) {
        return throttleCounters.computeIfAbsent(
                consumer,
                c ->
                        Counter.builder("orinuno.inbound.throttle")
                                .description(
                                        "Total POST /api/v1/parse/requests calls rejected by the"
                                                + " inbound rate limiter")
                                .tags(Tags.of("consumer", c))
                                .register(meterRegistry));
    }

    private static String sanitizeForLog(String value) {
        if (value == null) return "null";
        return value.replace('\n', '_').replace('\r', '_');
    }
}
