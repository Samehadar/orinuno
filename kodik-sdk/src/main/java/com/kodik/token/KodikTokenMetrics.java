package com.kodik.token;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * Exposes Prometheus gauges for each token tier so operators can alert when {@code stable} / {@code
 * unstable} go to zero. Values are read lazily from the {@link KodikTokenRegistry}.
 *
 * <p>ADR 0018 Phase 1.4b — @Component / @PostConstruct stripped. orinuno-app's
 * KodikSdkConfiguration wires the bean and invokes {@link #init(MeterRegistry)} explicitly.
 */
public class KodikTokenMetrics {

    private final KodikTokenRegistry registry;

    public KodikTokenMetrics(KodikTokenRegistry registry) {
        this.registry = registry;
    }

    public void init(MeterRegistry meterRegistry) {
        for (KodikTokenTier tier : KodikTokenTier.values()) {
            Gauge.builder("kodik.tokens.count", registry, r -> (double) r.countFor(tier))
                    .description("Count of Kodik tokens in a given tier")
                    .tags(Tags.of("tier", tier.getJsonKey()))
                    .register(meterRegistry);
        }
    }
}
