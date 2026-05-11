/*
 * JutsuSourceProperties — orinuno-source-jutsu config tree (ADR 0019 Phase 4.4).
 *
 * Slice of OrinunoProperties.providers.jutsu.* extracted into a standalone
 * @ConfigurationProperties so the per-source service stays self-contained and
 * doesn't depend on orinuno-app's OrinunoProperties. Property prefix `jutsu.*`
 * — env vars map via relaxed-binding (JUTSU_BASE_URL etc.).
 */
package com.orinuno.source.jutsu;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("jutsu")
public class JutsuSourceProperties {

    private String baseUrl = "https://jut.su";
    private String username = "";
    private String password = "";
    private double rateLimitRps = 1.0;
    private long sessionTtlMinutes = 240;
    private int loginTimeoutSeconds = 15;

    /** Live-fallback guards — cache-miss path to upstream. */
    private FallbackProperties fallback = new FallbackProperties();

    /** Catalog sync worker + notice-walk knobs (Phase 4.5). */
    private SyncProperties sync = new SyncProperties();

    public boolean hasCredentials() {
        return username != null && !username.isBlank() && password != null && !password.isBlank();
    }

    @Data
    public static class FallbackProperties {
        /** Master switch — false → cache-miss returns 503 instead of touching jut.su. */
        private boolean enabled = true;

        /** Separate token-bucket RPS for cache-miss traffic; default 0.5 = 1 every 2s. */
        private double rateLimitRps = 0.5;

        private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
        private NegativeCacheProperties negativeCache = new NegativeCacheProperties();

        @Data
        public static class CircuitBreakerProperties {
            private int windowSize = 20;
            private double failureRateThreshold = 0.5;
            private long openPauseSeconds = 60;
        }

        @Data
        public static class NegativeCacheProperties {
            private long ttlSeconds = 60;
            private long maxSize = 1000;
        }
    }

    /** ADR 0016 P1a Step 2 — catalog sync worker + notice-feed walker (Phase 4.5). */
    @Data
    public static class SyncProperties {
        /** Master switch — disables both schedulers when false. */
        private boolean enabled = false;

        private FullCrawlProperties fullCrawl = new FullCrawlProperties();
        private NoticeWalkProperties noticeWalk = new NoticeWalkProperties();

        @Data
        public static class FullCrawlProperties {
            private boolean enabled = false;
            private long intervalHours = 24;
            private long initialDelaySeconds = 300;
            private int maxPagesPerTick = 30;
        }

        @Data
        public static class NoticeWalkProperties {
            private boolean enabled = false;
            private long intervalMinutes = 15;
            private long initialDelaySeconds = 60;
            private int maxFeedsPerTick = 5;
            private boolean fetchInfoOnDiscovery = false;
            private int maxInfoFetchesPerTick = 10;
        }
    }
}
