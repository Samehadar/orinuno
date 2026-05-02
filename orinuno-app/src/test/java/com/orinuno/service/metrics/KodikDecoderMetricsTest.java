package com.orinuno.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KodikDecoderMetricsTest {

    private SimpleMeterRegistry registry;
    private KodikDecoderMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new KodikDecoderMetrics(registry);
    }

    @Test
    @DisplayName("recordDecodePath increments tagged counter once per call")
    void recordDecodePathIncrementsCounter() {
        metrics.recordDecodePath(KodikDecoderMetrics.DecodePath.SHORT_CIRCUIT_M3U8);
        metrics.recordDecodePath(KodikDecoderMetrics.DecodePath.SHORT_CIRCUIT_M3U8);
        metrics.recordDecodePath(KodikDecoderMetrics.DecodePath.CACHED_SHIFT_HIT);

        assertThat(counterValue("orinuno.decoder.path", "path", "short_circuit_m3u8"))
                .isEqualTo(2.0);
        assertThat(counterValue("orinuno.decoder.path", "path", "cached_shift_hit")).isEqualTo(1.0);
        assertThat(counterValue("orinuno.decoder.path", "path", "brute_force_new_shift"))
                .as("never incremented → no counter registered → 0.0")
                .isZero();
    }

    @Test
    @DisplayName("recordShiftHit caps shift to 0..25, otherwise tags as _none")
    void recordShiftHitClampsOutOfRange() {
        metrics.recordShiftHit(0);
        metrics.recordShiftHit(18);
        metrics.recordShiftHit(25);
        metrics.recordShiftHit(-1);
        metrics.recordShiftHit(99);

        assertThat(counterValue("orinuno.decoder.shift", "shift", "0")).isEqualTo(1.0);
        assertThat(counterValue("orinuno.decoder.shift", "shift", "18")).isEqualTo(1.0);
        assertThat(counterValue("orinuno.decoder.shift", "shift", "25")).isEqualTo(1.0);
        assertThat(counterValue("orinuno.decoder.shift", "shift", "_none")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordPickedQuality tags by bucket; null/blank → _none (ADR 0004 visibility)")
    void recordPickedQualityTagsCorrectly() {
        metrics.recordPickedQuality("720");
        metrics.recordPickedQuality("720");
        metrics.recordPickedQuality("480");
        metrics.recordPickedQuality(null);
        metrics.recordPickedQuality("");
        metrics.recordPickedQuality("1080"); // future-proof

        assertThat(counterValue("orinuno.decoder.quality", "quality", "720")).isEqualTo(2.0);
        assertThat(counterValue("orinuno.decoder.quality", "quality", "480")).isEqualTo(1.0);
        assertThat(counterValue("orinuno.decoder.quality", "quality", "1080")).isEqualTo(1.0);
        assertThat(counterValue("orinuno.decoder.quality", "quality", "_none")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("counter cardinality stays bounded — same tag value reuses the same counter")
    void counterReusedForSameTagValue() {
        for (int i = 0; i < 100; i++) {
            metrics.recordDecodePath(KodikDecoderMetrics.DecodePath.CACHED_SHIFT_HIT);
        }
        long count =
                registry.find("orinuno.decoder.path")
                        .tag("path", "cached_shift_hit")
                        .counters()
                        .size();
        assertThat(count).as("100 increments should map to a SINGLE counter").isEqualTo(1L);
        assertThat(counterValue("orinuno.decoder.path", "path", "cached_shift_hit"))
                .isEqualTo(100.0);
    }

    private double counterValue(String name, String tagKey, String tagValue) {
        var counter = registry.find(name).tag(tagKey, tagValue).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
