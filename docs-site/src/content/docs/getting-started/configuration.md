---
title: Configuration
description: Full reference for the orinuno.* namespace in application.yml — Kodik API, decoder, proxy pool, Playwright, storage, and security.
---

Every configurable value lives under the `orinuno.*` namespace and is bound to
`OrinunoProperties`. The defaults are deliberately conservative — see
[Responsible Use](/orinuno/legal/responsible-use/) before raising them.

## `application.yml` reference

```yaml
orinuno:
  kodik:
    api-url: https://kodik-api.com       # Kodik API base URL
    token: ""                              # Kodik API token (required)
    request-delay-ms: 500                  # Delay between API requests (ms)
  parse:
    rate-limit-per-minute: 30              # Max Kodik API calls per minute (token-bucket)
  decoder:
    timeout-seconds: 30                    # Per-link decoder timeout
    max-retries: 3                         # Max decode retry attempts
    link-ttl-hours: 20                     # CDN link TTL before refresh
    refresh-interval-ms: 3600000           # TTL-check interval (ms)
    refresh-batch-size: 50                 # Max links to refresh per cycle
  security:
    api-key: ""                            # API key for auth (empty = disabled)
  cors:
    allowed-origins: "*"                   # Comma-separated origins or "*"
  proxy:
    enabled: false                         # Enable proxy rotation
    rotation-strategy: round-robin         # Currently the only strategy
  storage:
    base-path: ./data/videos               # Local storage for downloaded videos
    max-disk-usage-mb: 10240               # Max disk usage (MB)
  playwright:
    enabled: true                          # Enable Playwright video fetcher
    headless: true                         # Headless Chromium mode
    page-timeout-seconds: 30               # Page operation timeout
    navigation-timeout-ms: 15000           # Navigation timeout
    video-wait-ms: 30000                   # Max wait for video URL interception
    hls-concurrency: 16                    # Parallel HLS segment download threads
  providers:
    jutsu:
      base-url: https://jut.su             # jut.su base URL
      username: ""                         # DLE username (premium decoder)
      password: ""                         # DLE password (premium decoder)
      rate-limit-rps: 1.0                  # Hard cap to be polite to jut.su
      session-ttl-minutes: 240             # Sticky cookie jar lifetime
      login-timeout-seconds: 15            # DLE login timeout
  jutsu:
    sync:                                  # ADR 0016 P1a — L1 catalog sync worker
      full-crawl-interval-hours: 48        # Walk JutsuClient.browseCatalog every 48h
      notice-interval-minutes: 5           # Poll the upstream notice feed every 5m
      notice-lock-ttl-minutes: 30          # Recover crashed-worker locks after 30m
    live-fallback:                         # ADR 0016 P1a — request-time DDoS guards
      enabled: true                        # Kill-switch (default false in prod profile)
      rate-limit:
        requests-per-second: 0.2           # Bucket4j RPS per consumer (api-key or IP)
      negative-cache:
        ttl-hours: 24                      # 404/410/null upstream cached for 24h
      buckets:                             # Caffeine cache for Bucket4j buckets
        expire-after-access-hours: 1       # Bucket entries idle-evicted after 1h
        max-size: 50000                    # Hard cap on the bucket cache size
```

## Environment variable mapping

Spring Boot maps environment variables to YAML paths with `_` → `-`. Common
ones:

| Env var | YAML path |
| --- | --- |
| `KODIK_TOKEN` | `orinuno.kodik.token` |
| `ORINUNO_API_KEY` | `orinuno.security.api-key` |
| `DECODER_LINK_TTL_HOURS` | `orinuno.decoder.link-ttl-hours` |
| `CORS_ALLOWED_ORIGINS` | `orinuno.cors.allowed-origins` |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` | Spring datasource properties |
| `JUTSU_USERNAME`, `JUTSU_PASSWORD` | `orinuno.providers.jutsu.username` / `password` |
| `JUTSU_SYNC_FULL_CRAWL_INTERVAL_HOURS` | `orinuno.jutsu.sync.full-crawl-interval-hours` |
| `JUTSU_SYNC_NOTICE_INTERVAL_MINUTES` | `orinuno.jutsu.sync.notice-interval-minutes` |
| `JUTSU_SYNC_NOTICE_LOCK_TTL_MINUTES` | `orinuno.jutsu.sync.notice-lock-ttl-minutes` |
| `JUTSU_LIVE_FALLBACK_ENABLED` | `orinuno.jutsu.live-fallback.enabled` |
| `JUTSU_LIVE_FALLBACK_RPS` | `orinuno.jutsu.live-fallback.rate-limit.requests-per-second` |
| `JUTSU_LIVE_FALLBACK_NEGATIVE_CACHE_TTL_HOURS` | `orinuno.jutsu.live-fallback.negative-cache.ttl-hours` |
| `JUTSU_LIVE_FALLBACK_BUCKETS_EXPIRE_HOURS` | `orinuno.jutsu.live-fallback.buckets.expire-after-access-hours` |
| `JUTSU_LIVE_FALLBACK_BUCKETS_MAX_SIZE` | `orinuno.jutsu.live-fallback.buckets.max-size` |

## Notes on specific knobs

- **`kodik.request-delay-ms`** — sleeps between calls to `kodik-api.com`. Lower it only against a private sandbox; never against the shared public endpoint.
- **`parse.rate-limit-per-minute`** — token bucket, refilled every
  `60_000 / max-permits` ms. Anything above what your token allows will get
  you rate-limited at the Kodik side.
- **`decoder.link-ttl-hours`** — mp4 URLs from `solodcdn.com` have a finite
  TTL. Pick a value shorter than the observed expiry; 20h is a safe
  default.
- **`playwright.hls-concurrency`** — 8 to 16 works well on a laptop; raise
  cautiously, the CDN throttles per-connection after some point.
- **`security.api-key`** — when set, all write and read endpoints except
  `/api/v1/health/*` require `X-API-KEY` on every request.
- **`jutsu.sync.full-crawl-interval-hours`** — how often
  `JutsuCatalogSyncService` walks the upstream catalogue end-to-end and
  upserts `jutsu_title` / `jutsu_episode`. The first crawl runs at boot.
  Lower values mean fresher data and more upstream traffic.
- **`jutsu.sync.notice-interval-minutes`** — incremental walk frequency.
  Reads `JutsuClient.getLatestNoticeFeed()` and applies only the delta
  since `jutsu_sync_state.last_notice_cursor`.
- **`jutsu.sync.notice-lock-ttl-minutes`** — `JutsuNoticeLockService`
  reclaims a stuck `notice_walk_in_progress=TRUE` row whose `updated_at`
  is older than this. Crash recovery; do not lower below the worst-case
  worker tick.
- **`jutsu.live-fallback.enabled`** — kill-switch for the request-time
  hybrid fallback. When `false`, jut.su endpoints serve **only** what's in
  the L1 cache; cache misses return 503. Default in
  `JutsuLiveFallbackProperties` is `false` (prod-safe); the dev profile
  flips it to `true` for the local demo UI.
- **`jutsu.live-fallback.rate-limit.requests-per-second`** — per-consumer
  Bucket4j RPS for the live-fallback path. Consumer is identified by
  `X-API-KEY` if present, otherwise the remote IP. Defaults to 0.2 rps to
  stay polite to jut.su; raise carefully.
- **`jutsu.live-fallback.negative-cache.ttl-hours`** — how long a 404/410
  / null upstream is cached. Transient errors (5xx, IO, timeout, drift)
  are **never** cached.
- **`jutsu.live-fallback.buckets.*`** — Caffeine eviction parameters for
  the Bucket4j bucket cache. Prevents unbounded memory growth on public
  traffic.

## Related

- [API overview → Security](/orinuno/api/overview/)
- [Operations → TTL refresh](/orinuno/operations/ttl-refresh/)
- [Operations → Proxy pool](/orinuno/operations/proxy-pool/)
