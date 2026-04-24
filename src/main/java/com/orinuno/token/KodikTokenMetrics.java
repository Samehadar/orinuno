package com.orinuno.token;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Exposes Prometheus gauges for each token tier so operators can alert when {@code stable} / {@code
 * unstable} go to zero. Values are read lazily from the {@link KodikTokenRegistry}.
 */
@Component
public class KodikTokenMetrics {

    private final KodikTokenRegistry registry;
    private final MeterRegistry meterRegistry;

    public KodikTokenMetrics(KodikTokenRegistry registry, MeterRegistry meterRegistry) {
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        for (KodikTokenTier tier : KodikTokenTier.values()) {
            Gauge.builder("kodik.tokens.count", registry, r -> (double) r.countFor(tier))
                    .description("Count of Kodik tokens in a given tier")
                    .tags(Tags.of("tier", tier.getJsonKey()))
                    .register(meterRegistry);
        }
    }
}
