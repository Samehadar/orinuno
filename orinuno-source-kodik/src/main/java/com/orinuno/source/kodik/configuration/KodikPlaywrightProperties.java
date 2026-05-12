/*
 * KodikPlaywrightProperties — ADR 0021 §D1b-3 (partial Block E2).
 *
 * Playwright + HLS-via-Playwright knobs for the DECODE-8 sniff-fallback
 * + future C3 download path. Replaces the legacy
 * OrinunoProperties.PlaywrightProperties + .HlsProperties subtrees.
 * Property prefix: orinuno.source-kodik.playwright.*. Defaults preserve
 * legacy orinuno-app values.
 */
package com.orinuno.source.kodik.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno.source-kodik.playwright")
public class KodikPlaywrightProperties {

    private boolean enabled = true;
    private boolean headless = true;
    private int pageTimeoutSeconds = 30;
    private int navigationTimeoutMs = 15000;
    private int videoWaitMs = 30000;
    private int hlsConcurrency = 16;
    private HlsProperties hls = new HlsProperties();

    @Data
    public static class HlsProperties {
        private int masterResolutionMaxDepth = 3;
        private int segmentRetryMaxAttempts = 4;
        private long segmentRetryBaseDelayMs = 250;
        private boolean failOnMissingSegment = false;
        private FfmpegMode ffmpegMode = FfmpegMode.SINGLE_INPUT;

        public enum FfmpegMode {
            SINGLE_INPUT,
            CONCAT_DEMUXER
        }
    }
}
