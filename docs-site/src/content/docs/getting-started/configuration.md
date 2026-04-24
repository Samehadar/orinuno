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

## Related

- [API overview → Security](/orinuno/api/overview/)
- [Operations → TTL refresh](/orinuno/operations/ttl-refresh/)
- [Operations → Proxy pool](/orinuno/operations/proxy-pool/)
