/*
 * KodikDecoderProperties — ADR 0021 §D1b-1 (partial Block E2).
 *
 * Decoder + maintenance + DECODE-8 fallback knobs. Replaces the legacy
 * OrinunoProperties.DecoderProperties subtree in orinuno-app for the
 * source-kodik-owned decoder stack. Property prefix:
 *   orinuno.source-kodik.decoder.*
 *
 * Defaults preserve the legacy orinuno-app values so dev/docker-compose
 * stays behaviour-identical without env tweaks.
 */
package com.orinuno.source.kodik.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno.source-kodik.decoder")
public class KodikDecoderProperties {

    private int timeoutSeconds = 30;
    private int maxRetries = 3;
    private int linkTtlHours = 20;
    private long refreshIntervalMs = 3600000;
    private int refreshBatchSize = 50;

    /**
     * DECODE-8 — when {@code true} AND Playwright is wired up, the orchestrator falls back to a
     * Playwright network-sniff decoder when the regex/JS path returns empty. Disabled by default
     * because Playwright is heavyweight (full Chromium); enable when you've observed regex breakage
     * in production and need a stop-gap.
     */
    private boolean sniffFallbackEnabled = false;

    private MaintenanceProperties maintenance = new MaintenanceProperties();

    /**
     * Bounds for the long-running decoder maintenance jobs ({@code refreshExpiredLinks}, {@code
     * retryFailedDecodes}). Without these caps a single bad batch could pin a worker thread for
     * tens of minutes and starve unrelated scheduled jobs.
     */
    @Data
    public static class MaintenanceProperties {
        private int maxBatchPerTick = 10;
        private long tickTimeoutSeconds = 600;
    }
}
