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
     * operations/downstream consumer-integration.
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
    private ProvidersProperties providers = new ProvidersProperties();

    /**
     * Settings for the alternative video providers we decode (Sibnet, Aniboom, JutSu). Most are
     * stateless decoders so they live without configuration; JutSu is special because real CDN URLs
     * are gated behind a {@code Jutsu+} subscription, so we ship a per-provider auth + rate-limit
     * block. Keeping providers under one prefix means future Aniboom/Sibnet auth (if they ever
     * introduce it) can hang off the same parent without renaming env vars.
     */
    @Data
    public static class ProvidersProperties {
        private JutsuProperties jutsu = new JutsuProperties();
    }

    /**
     * JutSu (jut.su) provider — DataLife Engine login + sticky cookie session + outbound rate
     * limit. See {@code docs/quirks-and-hacks.md} → "JutSu premium gating leaks &lt;source&gt; tags
     * with placeholder URLs" for the why.
     *
     * <ul>
     *   <li>{@code username/password} — DLE form fields. Empty by default; when blank the decoder
     *       runs in anonymous mode and returns {@code JUTSU_PREMIUM_REQUIRED} for gated episodes.
     *       NEVER commit real values; populate via {@code JUTSU_USERNAME / JUTSU_PASSWORD} env vars
     *       only.
     *   <li>{@code rate-limit-rps} — outbound requests per second to {@code jut.su}, hard-capped to
     *       protect the account from being flagged for API abuse. Default {@code 1.0} matches what
     *       a single human browsing tab generates. Floors at {@code 0.1} (1 req / 10s); raise only
     *       if you have a separate rate-limit agreement with jut.su.
     *   <li>{@code session-ttl-minutes} — how long the cached cookie jar is treated as fresh before
     *       we proactively re-login. The DLE cookies are valid for ~50 days (we observed {@code
     *       Max-Age} ~52 weeks on {@code dle_password}), but we re-login much earlier so a silent
     *       password rotation or account ban surfaces while operators are still on shift.
     *   <li>{@code base-url} — kept configurable so a staging mirror or HAR-replay server can be
     *       swapped in for tests. Production should never override this.
     * </ul>
     */
    @Data
    public static class JutsuProperties {
        private String baseUrl = "https://jut.su";
        private String username = "";
        private String password = "";
        private double rateLimitRps = 1.0;
        private long sessionTtlMinutes = 240;
        private int loginTimeoutSeconds = 15;

        /**
         * Drift canary probe knobs. The probe periodically calls a small fixed set of jut.su
         * endpoints (latest notice feed, OnePunch Man anime info, page 1 of the unfiltered
         * catalog). Drift signals observed during these calls are aggregated into the SDK drift
         * detector and read by {@code MultiSourceRanker} to decide whether to demote jut.su.
         *
         * <p>Disabled by default in tests; enabled in production via {@code
         * orinuno.providers.jutsu.drift-probe.enabled=true}.
         */
        private DriftProbeProperties driftProbe = new DriftProbeProperties();

        /**
         * Catalog sync worker (ARCH-0016 P1a Step 2). When enabled, periodically full-crawls the
         * jut.su anime catalog into the local L1 cache ({@code jutsu_title}) so REST reads can be
         * served from the DB instead of hammering upstream on every request. Disabled by default —
         * flip {@code enabled} after applying the P1a Liquibase migrations and confirming the
         * outbound rate is acceptable for your jut.su account / IP.
         */
        private SyncProperties sync = new SyncProperties();

        /**
         * Live fallback guards (ARCH-0016 P1a Step 3.B). Controls the cache-miss path: a dedicated
         * rate-limit bucket separate from the SDK's main bucket, a rolling-window circuit breaker
         * that auto-trips after sustained failures, and a negative cache that short-circuits
         * repeated lookups for slugs we just failed to resolve. When {@code enabled} is false, the
         * cache-miss path returns 503 instead of touching jut.su.
         */
        private FallbackProperties fallback = new FallbackProperties();

        public boolean hasCredentials() {
            return username != null
                    && !username.isBlank()
                    && password != null
                    && !password.isBlank();
        }

        @Data
        public static class DriftProbeProperties {
            private boolean enabled = false;

            /** Minutes between probe runs. Default 6 hours = 360 minutes. */
            private long intervalMinutes = 360;

            /** Initial delay before the first probe run, in seconds. */
            private long initialDelaySeconds = 60;

            /**
             * Anime slug used for the info-page canary probe. Must be a slug that exists on the
             * site for the lifetime of the probe; defaults to OnePunch Man because we have a
             * captured fixture for it.
             */
            private String canonicalSlug = "onepuunchman";
        }

        /**
         * Knobs for {@code JutsuCatalogSyncService} (ARCH-0016 P1a Step 2). The full-crawl loop
         * walks {@code POST /anime/} page by page, mapping each entry into {@code jutsu_title}. It
         * resumes from the persisted {@code full_crawl_last_page} after a crash and restarts at
         * page 1 once the previous full crawl completed.
         *
         * <p>Default cadence (24 h, max 30 pages per tick = 900 titles per tick at 1 RPS) is
         * conservative; the whole catalog (~3500 entries on jut.su, growing) finishes in roughly 4
         * ticks. Tighten the interval / raise {@code maxPagesPerTick} only if your account has a
         * separate rate-limit agreement with jut.su — the SDK still rate-limits at {@code
         * orinuno.providers.jutsu.rate-limit-rps} regardless.
         */
        @Data
        public static class SyncProperties {
            private boolean enabled = false;
            private FullCrawlProperties fullCrawl = new FullCrawlProperties();
            private NoticeWalkProperties noticeWalk = new NoticeWalkProperties();
            private CatalogIngestionProperties catalogIngestion = new CatalogIngestionProperties();

            /**
             * Bridge between the jut.su L1 cache and the L3 universal canonical catalog (ARCH-0016
             * P1b Step 1.C). When enabled, every {@code jutsu_title} upsert in the sync worker
             * triggers a synchronous call to {@code CatalogPublicApi.findOrCreateContent} which
             * materialises (or updates) the canonical row and binds the {@code (JUTSU, slug)} pair
             * via {@code catalog_content_external_id}.
             *
             * <p>Disabled by default while the L3 surface is still under construction — enable once
             * a full-crawl tick is known to be safe end-to-end on the target deployment. Failures
             * inside the resolver are caught and logged WARN; sync never aborts because of an L3
             * hiccup.
             */
            @Data
            public static class CatalogIngestionProperties {
                private boolean enabled = false;
            }

            @Data
            public static class FullCrawlProperties {
                private boolean enabled = false;

                /** Hours between full-crawl ticks. Default 24h. */
                private long intervalHours = 24;

                /** Initial delay before the first tick, in seconds. */
                private long initialDelaySeconds = 300;

                /**
                 * Hard cap on the number of catalog pages fetched per tick. Each page costs one
                 * outbound request against the jut.su RPS budget; default 30 pages × 30 entries =
                 * 900 titles per tick.
                 */
                private int maxPagesPerTick = 30;
            }

            /**
             * Notice-feed incremental walker (Step 2.B). Polls jut.su's "upcoming releases" notice
             * feed at a fast cadence to discover newly-published slugs between the slow full-crawl
             * ticks.
             *
             * <p>The walker maintains a persistent {@code noticeCursor} on {@code jutsu_sync_state}
             * and only walks feeds newer than the saved cursor; on a quiet site it costs exactly
             * one homepage GET + one feed fetch per tick. On the very first tick (cursor null) the
             * walker just records the latest cursor and exits without walking any feeds — we never
             * want to backfill the entire notice history retroactively.
             */
            @Data
            public static class NoticeWalkProperties {
                private boolean enabled = false;

                /** Minutes between notice-walk ticks. Default 15min. */
                private long intervalMinutes = 15;

                /** Initial delay before the first tick, in seconds. */
                private long initialDelaySeconds = 60;

                /**
                 * Hard cap on the number of notice-feed pages walked per tick. The site keeps
                 * roughly 50 entries per page; default 5 pages = 250 entries (≈ a busy week of
                 * episode releases on jut.su). Tighten if the site backfills aggressively.
                 */
                private int maxFeedsPerTick = 5;

                /**
                 * When {@code true}, every previously-unseen slug discovered in the notice feed
                 * triggers a synchronous {@code getAnimeInfo(slug)} call so the L1 row gets full
                 * info-page metadata immediately. When {@code false} (the default), the walker just
                 * remembers the slug exists and lets the next full-crawl tick pick it up.
                 */
                private boolean fetchInfoOnDiscovery = false;

                /**
                 * Hard cap on the number of {@code getAnimeInfo} calls fired per tick. Only honored
                 * when {@link #fetchInfoOnDiscovery} is true; protects the rate-limit budget on
                 * busy days.
                 */
                private int maxInfoFetchesPerTick = 10;
            }
        }

        /**
         * Knobs for {@code JutsuLiveFallbackService} (ARCH-0016 P1a Step 3.B). The fallback layer
         * sits between the L1 cache and the SDK on cache-miss; it exists to keep our service stable
         * when jut.su is degraded.
         *
         * <p>Three independent guards stack on the cache-miss path:
         *
         * <ol>
         *   <li>A dedicated rate-limit bucket (default 0.5 RPS) separate from the sync worker's
         *       budget so a sudden flood of cache-misses can't starve the worker.
         *   <li>A rolling-window circuit breaker: when the failure rate over the last {@code
         *       window-size} fallback calls exceeds {@code failure-rate-threshold} the breaker
         *       opens for {@code open-pause-seconds}, then transitions to half-open and lets
         *       exactly one probe through.
         *   <li>A negative cache: a slug / catalog query whose live fetch just failed gets
         *       remembered for {@code negative-cache.ttl-seconds} so a thundering herd of retries
         *       doesn't fire the same doomed request 100x in a row.
         * </ol>
         *
         * <p>Manual override: setting {@code enabled=false} short-circuits the entire fallback path
         * — REST endpoints return 503 Service Unavailable on cache-miss instead of trying to hit
         * jut.su. Useful when jut.su is in known maintenance or we want to fail closed.
         */
        @Data
        public static class FallbackProperties {

            /** Master switch. {@code false} ⇒ all cache-miss requests return 503. */
            private boolean enabled = true;

            /**
             * Outbound RPS for the cache-miss path. Distinct from {@code
             * orinuno.providers.jutsu.rate-limit-rps} (which still applies to the sync worker's
             * catalog / notice / info calls). Default 0.5 RPS = one fallback request every 2
             * seconds.
             */
            private double rateLimitRps = 0.5;

            private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
            private NegativeCacheProperties negativeCache = new NegativeCacheProperties();

            /**
             * Rolling-window circuit breaker tuning. The breaker observes only fallback outcomes —
             * sync-worker failures don't trip it (they have their own retry / drift handling).
             */
            @Data
            public static class CircuitBreakerProperties {
                /**
                 * Number of recent fallback outcomes considered when computing the failure rate.
                 * Smaller windows trip faster on steady degradation; larger windows tolerate
                 * spikes. Default 20.
                 */
                private int windowSize = 20;

                /**
                 * Failure rate (0.0 - 1.0) above which the breaker opens once the window is full.
                 * Default 0.5 = open when half the recent calls failed.
                 */
                private double failureRateThreshold = 0.5;

                /**
                 * How long the breaker stays open before transitioning to half-open. During this
                 * pause, all fallback requests short-circuit immediately. Default 60 seconds.
                 */
                private long openPauseSeconds = 60;
            }

            /**
             * Negative-cache tuning. When a fallback live call fails, the failure is remembered for
             * a short TTL so concurrent / repeat requests with the same key don't re-fire the same
             * doomed call. Successful results are NOT cached here — they go to the L1 cache via the
             * sync workers, on a much longer TTL.
             */
            @Data
            public static class NegativeCacheProperties {
                /**
                 * How long a failed-fallback marker lives before the next request is allowed
                 * through. Should be small (default 30s) — too long and a transient blip extends
                 * into a perceived outage; too short and we don't deduplicate fast enough.
                 */
                private long ttlSeconds = 30;

                /**
                 * Max number of distinct keys cached. Caffeine evicts least-recently-accessed once
                 * full. Default 10000 covers the entire jut.su catalog with headroom.
                 */
                private long maxSize = 10_000;
            }
        }
    }

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
        private HlsProperties hls = new HlsProperties();
    }

    /**
     * DOWNLOAD-PARALLEL — knobs for the HLS segment fetch + ffmpeg remux pipeline. Defaults
     * preserve legacy behaviour: master-playlist resolution stays on (cheap, fixes silently broken
     * downloads); 5xx/429 retries are conservative (4 attempts); ffmpeg stays in single-input mode
     * (concat-demuxer is opt-in for very large playlists where the giant intermediate {@code .ts}
     * is a problem).
     */
    @Data
    public static class HlsProperties {
        /**
         * Max recursion depth when resolving an HLS master playlist down to a media playlist. Kodik
         * typically only nests once but a malicious / misconfigured CDN could loop us forever; cap
         * defensively.
         */
        private int masterResolutionMaxDepth = 3;

        /** Max attempts per segment when the upstream returns a retriable HTTP status or IO. */
        private int segmentRetryMaxAttempts = 4;

        /** Base sleep (ms) between retries; scales linearly with attempt index. */
        private long segmentRetryBaseDelayMs = 250;

        /**
         * When {@code true}, abort the download if any segment ends up empty (HTTP non-2xx that
         * exhausted retries, IO that exhausted retries, etc.). Default {@code false} preserves
         * legacy behaviour where holes are silently skipped — historically this was deliberate
         * because Kodik's CDN flaps occasionally and a 99.x % file is usually playable. Flip to
         * {@code true} when you want hard failures (e.g. for archival downloads).
         */
        private boolean failOnMissingSegment = false;

        /**
         * ffmpeg remux mode. {@code single-input} concatenates segments to one big {@code .ts} and
         * runs {@code ffmpeg -i big.ts -c copy out.mp4} (legacy / default — usually optimal because
         * Kodik playlists are small). {@code concat-demuxer} keeps segments separate, writes a
         * {@code concat.txt} manifest, and runs {@code ffmpeg -f concat -safe 0 -i concat.txt -c
         * copy out.mp4} — useful for very large playlists where the giant intermediate {@code .ts}
         * exhausts disk.
         */
        private FfmpegMode ffmpegMode = FfmpegMode.SINGLE_INPUT;

        public enum FfmpegMode {
            SINGLE_INPUT,
            CONCAT_DEMUXER
        }
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
