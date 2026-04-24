package com.orinuno.drift;

import lombok.Data;

/**
 * Configuration for {@link DriftDetector}. Domain-neutral so the {@code com.orinuno.drift} package
 * can be lifted into a standalone artifact when a second consumer appears (see BACKLOG,
 * TrafficAnalyzer).
 *
 * <p>Defaults: enabled, sample first 10 items per response. Set {@link #enabled} to {@code false}
 * to make every {@link DriftDetector} method a cheap no-op without touching call sites.
 */
@Data
public class DriftSamplingProperties {

    private boolean enabled = true;
    private ItemSampling itemSampling = new ItemSampling();

    @Data
    public static class ItemSampling {
        private ItemSamplingMode mode = ItemSamplingMode.FIRST_N;
        private int limit = 10;
    }

    public static DriftSamplingProperties defaults() {
        return new DriftSamplingProperties();
    }
}
