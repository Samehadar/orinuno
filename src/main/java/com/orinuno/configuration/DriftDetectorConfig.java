package com.orinuno.configuration;

import com.orinuno.drift.DriftDetector;
import com.orinuno.drift.DriftSamplingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code com.orinuno.drift} package into Spring. The detector itself stays
 * domain-neutral; only this configuration class knows about {@link OrinunoProperties}, which keeps
 * the door open for extracting {@code com.orinuno.drift} into a separate artifact later (see
 * BACKLOG.md, TrafficAnalyzer roadmap).
 */
@Slf4j
@Configuration
public class DriftDetectorConfig {

    @Bean
    public DriftDetector driftDetector(OrinunoProperties properties) {
        DriftSamplingProperties cfg = properties.getDrift();
        log.info(
                "DriftDetector enabled={} itemSampling.mode={} limit={}",
                cfg.isEnabled(),
                cfg.getItemSampling().getMode(),
                cfg.getItemSampling().getLimit());
        return new DriftDetector(cfg);
    }
}
