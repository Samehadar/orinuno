package com.orinuno.configuration;

import com.kodik.client.http.RotatingUserAgentProvider;
import com.kodik.drift.DriftDetector;
import com.kodik.drift.DriftSamplingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the two gateway-level {@code com.kodik.*} utilities into Spring:
 *
 * <ul>
 *   <li>{@link DriftDetector} — drift sampling shared across every SDK; reads {@link
 *       OrinunoProperties#getDrift()}.
 *   <li>{@link RotatingUserAgentProvider} — stateless User-Agent factory consumed by {@code
 *       JutsuSdkConfiguration}, {@code SibnetSdkConfiguration}, and {@code
 *       AniboomSdkConfiguration}. Was previously published by {@code
 *       kodik-sdk-spring-boot-starter}'s auto-config; that starter was dropped from orinuno-app's
 *       pom in the ADR 0021 cleanup (orinuno-app no longer hosts the Kodik write-path, so the
 *       starter's other beans were dead weight). The @Bean lives here so the three SDK configs do
 *       not have to each construct one and so a future host can still override it
 *       via @ConditionalOnMissingBean.
 * </ul>
 *
 * <p>Both beans are in the gateway-level allow-list enforced by the ADR 0021 §E1 ArchUnit guard
 * (BoundedContextArchitectureTest).
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

    @Bean
    @ConditionalOnMissingBean
    public RotatingUserAgentProvider rotatingUserAgentProvider() {
        return new RotatingUserAgentProvider();
    }
}
