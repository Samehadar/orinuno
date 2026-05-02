package com.orinuno.configuration;

import com.kodik.sdk.drift.DriftSamplingProperties;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "orinuno")
public class OrinunoProperties {

    private KodikProperties kodik = new KodikProperties();
    private ParseProperties parse = new ParseProperties();
    private DecoderProperties decoder = new DecoderProperties();
    private ProxyProperties proxy = new ProxyProperties();
    private StorageProperties storage = new StorageProperties();
    private RequestsProperties requests = new RequestsProperties();

    @Data
    public static class KodikProperties {
        private String apiUrl = "https://kodik-api.com";
        private String token = "";
        private long requestDelayMs = 500;
        private String tokenFile = "./data/kodik_tokens.json";
        private long validationIntervalMinutes = 360;
        private boolean autoDiscoveryEnabled = true;
        private boolean bootstrapFromEnv = true;
        private int tokenFailoverMaxAttempts = 3;
        private boolean validateOnStartup = true;

        /**
         * Cooldown before {@link com.orinuno.token.KodikTokenValidator#validateAll()} re-probes
         * tokens that currently sit in the {@code dead} tier. Without this, a single transient
         * network failure during the first {@code validate-on-startup} run would permanently exile
         * a token: {@code validateAll()} historically skipped DEAD entirely. Default 24h gives
         * Kodik plenty of time to recover from outages while still letting valid tokens heal
         * automatically.
         */
        private long deadRevalidationIntervalMinutes = 1440;
    }

    @Data
    public static class ParseProperties {
        private int rateLimitPerMinute = 30;
        private InboundRateLimitProperties inboundRateLimit = new InboundRateLimitProperties();
    }

    /**
     * Inbound rate limit applied to {@code POST /api/v1/parse/requests}. Per-consumer
     * (X-Created-By) token bucket. Surfaced as the {@code orinuno_inbound_throttle_total}
     * Prometheus counter and the integration health endpoint. See
     * operations/parser-kodik-integration.
     */
    @Data
    public static class InboundRateLimitProperties {
        private boolean enabled = true;
        private int requestsPerMinute = 60;
    }

    @Data
    public static class DecoderProperties {
        private int timeoutSeconds = 30;
        private int maxRetries = 3;
        private int linkTtlHours = 20;
        private long refreshIntervalMs = 3600000;
        private int refreshBatchSize = 50;
        private MaintenanceProperties maintenance = new MaintenanceProperties();

        /**
         * DECODE-8 — when {@code true} AND Playwright is wired up, the orchestrator falls back to a
         * Playwright network-sniff decoder when the regex/JS path returns empty. Disabled by
         * default because Playwright is heavyweight (full Chromium); enable when you've observed
         * regex breakage in production and need a stop-gap.
         */
        private boolean sniffFallbackEnabled = false;
    }

    /**
     * Bounds for the long-running decoder maintenance jobs ({@code refreshExpiredLinks}, {@code
     * retryFailedDecodes}). Without these caps a single bad batch — for example, all 50 variants
     * timing out under VPN-induced geo-block — could pin a worker thread for tens of minutes and
     * starve unrelated scheduled jobs. See TECH_DEBT TD-PR-5.
     */
    @Data
    public static class MaintenanceProperties {
        private int maxBatchPerTick = 10;
        private long tickTimeoutSeconds = 600;
    }

    private PlaywrightProperties playwright = new PlaywrightProperties();
    private SecurityProperties security = new SecurityProperties();
    private CorsProperties cors = new CorsProperties();
    private CacheProperties cache = new CacheProperties();
    private DriftSamplingProperties drift = new DriftSamplingProperties();
    private CalendarProperties calendar = new CalendarProperties();
    private DumpsProperties dumps = new DumpsProperties();

    @Data
    public static class SecurityProperties {
        private String apiKey = "";
    }

    @Data
    public static class CorsProperties {
        private List<String> allowedOrigins = List.of("*");
    }

    @Data
    public static class ProxyProperties {
        private boolean enabled = false;
        private String rotationStrategy = "round-robin";
    }

    @Data
    public static class StorageProperties {
        private String basePath = "./data/videos";
        private long maxDiskUsageMb = 10240;
    }

    @Data
    public static class PlaywrightProperties {
        private boolean enabled = true;
        private boolean headless = true;
        private int pageTimeoutSeconds = 30;
        private int navigationTimeoutMs = 15000;
        private int videoWaitMs = 30000;
        private int hlsConcurrency = 16;
    }

    @Data
    public static class CacheProperties {
        private ReferenceCacheProperties reference = new ReferenceCacheProperties();
    }

    @Data
    public static class ReferenceCacheProperties {
        private boolean enabled = true;
        private long ttlSeconds = 21_600;
    }

    @Data
    public static class RequestsProperties {
        private long workerPollMs = 2_000;
        private long staleRecoveryMs = 60_000;
        private long staleAfterMs = 300_000;
        private long progressFlushMs = 1_000;
        private int maxRetries = 3;
        private int defaultPageLimit = 50;
        private int maxPageLimit = 200;
    }

    /**
     * On-demand fetcher for the public Kodik calendar dump (IDEA-AP-5). Endpoint is unauthenticated
     * but heavy (~few MB), so we cap response size, cache aggressively (5 min TTL), and use
     * conditional GET (ETag / Last-Modified). Disable {@code enabled} to fail fast on the
     * controller without making upstream calls — useful when the dump is reported broken.
     */
    @Data
    public static class CalendarProperties {
        private boolean enabled = true;
        private String url = "https://dumps.kodikres.com/calendar.json";
        private long cacheTtlSeconds = 300;
        private long requestTimeoutSeconds = 10;
        private long maxResponseBytes = 4L * 1024 * 1024;
        private DeltaWatcherProperties deltaWatcher = new DeltaWatcherProperties();

        /**
         * CAL-6 — diff every Kodik calendar fetch against the previously persisted state and emit
         * one outbox event per detected delta. Disabled by default so existing deployments without
         * the new tables stay green; flip {@code enabled} after applying the Liquibase migration.
         */
        @Data
        public static class DeltaWatcherProperties {
            private boolean enabled = false;
            private long pollIntervalMinutes = 5;
            private long initialDelaySeconds = 60;
        }
    }

    /**
     * Public Kodik dump endpoints (DUMP-1). The dumps live at {@code
     * https://dumps.kodikres.com/{calendar,serials,films}.json}. We track them with HEAD-only
     * requests by default — the bodies are large (~80 KB / ~175 MB / ~82 MB) and we only need to
     * know "did the dump change" + the rolling timestamp ("when did we last see Kodik publish a
     * fresh dump?"). DUMP-2 will add opt-in body downloads for bootstrap; until then, {@code
     * downloadBody} stays {@code false}.
     *
     * <p>Set {@code enabled=false} to disable the watcher entirely (e.g. in CI, where we don't want
     * to hit Kodik on every test run). The default polling cadence (1 hour) is intentionally
     * conservative: dumps are refreshed by Kodik less often than that, and HEAD-only adds zero
     * meaningful load to their CDN.
     */
    @Data
    public static class DumpsProperties {
        private boolean enabled = false;
        private String baseUrl = "https://dumps.kodikres.com";
        private long pollIntervalMinutes = 60;
        private long initialDelaySeconds = 30;
        private long requestTimeoutSeconds = 30;
        private boolean downloadBody = false;
        private DumpEntry calendar = new DumpEntry(true, "calendar.json");
        private DumpEntry serials = new DumpEntry(true, "serials.json");
        private DumpEntry films = new DumpEntry(true, "films.json");

        @Data
        @lombok.NoArgsConstructor
        @lombok.AllArgsConstructor
        public static class DumpEntry {
            private boolean enabled;
            private String path;
        }
    }
}
