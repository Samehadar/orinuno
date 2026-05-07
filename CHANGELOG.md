# Changelog

All notable changes to this project are documented here. The format is loosely
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project follows the SemVer-flavoured `0.x` cycle until the public API
stabilises. The changelog is hand-written; commit messages remain the
authoritative log of every change.

## [Unreleased]

### ARCH-0016 P1a — facets in L1 + poster proxy (2026-05-08)

Restored full live-SDK card chrome on the demo UI catalog/search/anime tabs after
P1a's DB-first switch.

- **`jutsu_title` schema**: new columns `genres VARCHAR(500)`, `types
  VARCHAR(200)`, `movie_count INT` (Liquibase migration
  `20260508010000_add_facets_to_jutsu_title.sql`). `genres` / `types` are stored
  as sorted CSV slugs because the L1 endpoints don't filter on them server-side
  (filter requests force the live-fallback path; direct DB hits don't need a
  join table).
- **`JutsuCatalogSyncService`**: `catalogEntryToTitle` and `animeInfoToTitle`
  now persist genre / type slugs (sorted, CSV-joined) and movie count. The
  next full crawl backfills every existing row via the COALESCE upsert.
- **`JutsuCatalogEntryDto.fromTitle` / `JutsuAnimeInfoDto.fromTitleWithEpisodes`**:
  CSV → `List<String>` round-trip, so the wire shape now matches the live SDK
  one-for-one (`genres[]`, `types[]`, `movieCount`).
- **`JutsuPosterProxyController`** (new): `GET /api/v1/sources/jutsu/poster
  ?url=…`. Pass-through proxy whitelisted to `gen.jut.su` / `static.jut.su` /
  `jut.su` hosts. Adds `Cache-Control: public, max-age=86400`. Required because
  some browsers / CDN regions reject jut.su poster URLs cross-origin (referer
  / Cloudflare policy), so the demo UI was rendering cards without posters.
- **Demo UI** (`demo/src/views/JutsuView.vue`): `posterSrc()` helper wraps
  jut.su URLs through the proxy; non-jut.su URLs pass through unchanged.

### ARCH-0016 P1a — wire-shape uniformity follow-up (2026-05-08)

DB-first `jut.su` endpoints (`/catalog`, `/search`, `/anime/{slug}`, `/episode`)
were temporarily returning a separate `JutsuTitle*Dto` family on cache hits,
which broke the demo UI: posters disappeared (`thumbnailUrl` → `posterUrl`),
titles flipped (`title` → `titleRu`), pagination broke (`hasMore` → `pageSize +
totalElements`), and the anime info page lost its `seasons[]` block. The
controller now projects every L1 row onto the same `JutsuCatalogPageDto`,
`JutsuAnimeInfoDto`, and `JutsuEpisodeMetaDto` the live SDK already returns,
so the wire shape is identical regardless of cache hit / fallback.

- Removed the four transitional DTOs (`JutsuTitleDto`, `JutsuTitlePageDto`,
  `JutsuTitleWithEpisodesDto`, `JutsuStoredEpisodeDto`).
- Added factory methods `JutsuCatalogEntryDto.fromTitle(JutsuTitle)`,
  `JutsuCatalogPageDto.fromTitlePage(...)`,
  `JutsuAnimeInfoDto.fromTitleWithEpisodes(JutsuTitle, List<JutsuEpisode>)`,
  and `JutsuEpisodeMetaDto.fromStored(JutsuEpisode, @Nullable JutsuTitle)`.
  L1 does not currently store genre / type slugs, so they are emitted as
  empty arrays — supply `genres` / `types` query params to force the
  live-fallback path when those facets are required.
- `JutsuApiControllerTest` updated to the unified contract.

### ARCH-0016 P1a — jut.su L1 cache + hybrid live-fallback (2026-05-07)

First implementation step of [ADR 0016](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0016-architecture-trajectory.md):
the per-source raw cache (L1) for jut.su, an incremental sync worker driven by
the upstream notice feed, and a hybrid request-time read path that serves from
DB by default and falls back to the live SDK with full DDoS protection on
cache misses.

**Liquibase migrations** (`com/orinuno/db/changelog/jutsu/`):

- `jutsu_title` — slug-keyed mirror of upstream catalogue rows (title_ru / title_en / status / poster URL / last_synced_at).
- `jutsu_episode` — `(title_slug, season, episode)` PK; mirrors per-episode metadata (embedUrl, qualities, last_synced_at).
- `jutsu_sync_state` — singleton (`id=1`) row tracking `last_full_crawl_at`, `last_notice_cursor`, and an in-progress flag for the notice walk.

**`JutsuCatalogSyncService` (sync worker, `@Scheduled`)**:

- Full crawl every `JUTSU_SYNC_FULL_CRAWL_INTERVAL_HOURS` (default 48h).
  Walks the `JutsuClient.browseCatalog(...)` paginator, upserts `jutsu_title` /
  `jutsu_episode`, then explicitly invalidates `JutsuStalenessTracker` so the
  freshly-rebuilt cache reports `X-Sync-Stale-Seconds: 0`.
- Incremental notice walk every `JUTSU_SYNC_NOTICE_INTERVAL_MINUTES` (default
  5m). Reads `JutsuClient.getLatestNoticeFeed()`, compares the upstream cursor
  against `jutsu_sync_state.last_notice_cursor`, processes only the delta, and
  advances the saved cursor to the newest seen `notice_id`. Logs a warning
  when the gap exceeds the upstream page size — the next full crawl reconciles
  it.

**`JutsuNoticeLockService` (new `@Service`)**:

- Owns the `@Transactional` lock acquisition for the singleton `jutsu_sync_state`
  row. Lives in its own bean so Spring AOP applies the transactional proxy
  (avoids the self-invocation pitfall a single-class implementation hits).
- Atomic acquire-or-recover: `UPDATE jutsu_sync_state SET notice_walk_in_progress = TRUE
  WHERE id = 1 AND (notice_walk_in_progress = FALSE OR updated_at < :staleBefore)`.
  `JUTSU_SYNC_NOTICE_LOCK_TTL_MINUTES` (default 30m) defines the staleness
  window — orinuno automatically takes back a lock left by a crashed worker
  after that.

**`JutsuLiveFallbackService` (request-time DDoS guards)**:

- `dispatchReactive(slug, consumerKey, refresh, apiKey, Supplier<Mono<T>>)`:
  fully reactive entry point used by every `JutsuApiController` cache-miss /
  `?refresh=true` branch. No `.block()` ever runs on the WebFlux event loop.
- Bucket4j RPS rate limit per consumer (`JUTSU_LIVE_FALLBACK_RPS`, default
  0.2 rps). Buckets are stored in a Caffeine cache (`JUTSU_LIVE_FALLBACK_BUCKETS_*`)
  with `expireAfterAccess` to bound memory growth on public traffic.
- Caffeine negative cache (`JUTSU_LIVE_FALLBACK_NEGATIVE_CACHE_TTL_HOURS`,
  default 24h). Populated **only** for HTTP 404 / 410 / null upstream — 5xx,
  IOException, TimeoutException, and `JutsuDriftException` surface as 502 with
  the `UPSTREAM_ERROR` outcome and never poison the cache.
- Kill-switch (`JUTSU_LIVE_FALLBACK_ENABLED`). Default `false` in
  `JutsuLiveFallbackProperties` (prod-safe by default), explicitly set to `true`
  in dev `application.yml`.
- Outcome metric `jutsu.live_fallback.outcome.total` (counter, tag
  `outcome=DB_HIT|LIVE_HIT|NEGATIVE_CACHE|RATE_LIMITED|KILL_SWITCH|UPSTREAM_ERROR`)
  scrapeable via `/actuator/prometheus`.

**`JutsuStalenessTracker` (new `@Component`)**:

- Caffeine-backed cache (30s TTL) for `(now - jutsu_sync_state.last_full_crawl_at).seconds`,
  surfaced as `X-Sync-Stale-Seconds` on every jut.su API response. Removes the
  per-request SQL roundtrip a naive implementation would incur. Invalidated by
  `JutsuCatalogSyncService.fullCrawl()` on success.

**`JutsuApiController` (request path)**:

- `GET /api/v1/sources/jutsu/catalog` and `/search` are DB-first against
  `jutsu_title`. `?refresh=true` (and unsupported filter combinations) bypass
  the DB and go through the live-fallback dispatcher.
- `GET /api/v1/sources/jutsu/anime/{slug}` and `/episode` are DB-first;
  cache miss triggers `JutsuClient.getAnimeInfo` / `getEpisodeMeta` via the
  live-fallback, and successful fallbacks are upserted into `jutsu_title` /
  `jutsu_episode` so the next caller hits the cache.
- `GET /api/v1/sources/jutsu/notice` and `/notice/stream` stay live-only on
  purpose — the notice feed *is* the change feed, caching it provides no value.
- `GET /api/v1/sources/jutsu/drift` stays live as before.
- Every cached/live response carries `X-Sync-Stale-Seconds`. Every error
  response from the live-fallback adds a `Retry-After` header when applicable.

**Operational notes:**

- New env vars: `JUTSU_SYNC_FULL_CRAWL_INTERVAL_HOURS`, `JUTSU_SYNC_NOTICE_INTERVAL_MINUTES`,
  `JUTSU_SYNC_NOTICE_LOCK_TTL_MINUTES`, `JUTSU_LIVE_FALLBACK_ENABLED`,
  `JUTSU_LIVE_FALLBACK_RPS`, `JUTSU_LIVE_FALLBACK_NEGATIVE_CACHE_TTL_HOURS`,
  `JUTSU_LIVE_FALLBACK_BUCKETS_EXPIRE_HOURS`, `JUTSU_LIVE_FALLBACK_BUCKETS_MAX_SIZE`.
  See `docs-site/.../getting-started/configuration.md` for the full reference.
- One known follow-up: `TD-JUTSU-XFF` (consumerKey doesn't honour
  `X-Forwarded-For`) — irrelevant for single-instance deployments, but worth
  adding once orinuno is fronted by a load balancer.

## [SDK-SPLIT 2026-05-03] — API tier + per-provider standalone SDKs

The "SDK split" is a five-step refactor that moved every video provider out of
the orinuno-app monolith into its own Maven module while keeping the public
HTTP contract identical. After this change, an external project can depend on
just the SDK module for the provider it needs (`jutsu-sdk`, `sibnet-sdk`,
`aniboom-sdk`) without taking on Spring Boot, MyBatis, MySQL, Liquibase or any
orinuno-specific type.

The four code steps shipped as commits `e537c07` (Steps 1+2 together) →
`d0b1f26` (Step 3) → `c09f283` (Step 4). Each step is independently
reverify-able; combined, they reduce the orinuno-app source tree by ~1.1k
lines while adding ~3.4k lines of standalone SDK modules + tests +
documentation. Step 5 (this CHANGELOG, README map, project-structure update)
is the docs-only follow-up.

### Step 1 — Per-source API tier

- **New** `GET /api/v1/sources` — capabilities listing for all four providers
  (id, displayName, operations, credentialsRequired, credentialsConfigured,
  notes). Safe to call without authentication; the demo UI's `/sources` view
  consumes it directly.
- **New** `POST /api/v1/sources/{provider}/decode` — stateless ad-hoc decode
  dispatch keyed by path segment (`kodik`, `sibnet`, `aniboom`, `jutsu`).
  Returns a uniform `ProviderDecodeResult` shape regardless of provider.
- **New** `GET /api/v1/anime/{contentId}/episodes/{season}/{episode}/sources`
  and `GET /api/v1/anime/by-kinopoisk/{kpId}/episodes/{season}/{episode}/sources`
  — resource-style ranked candidates for one episode.
- **New** `GET /api/v1/sources/jutsu/stream` — canonical alias for the JutSu
  CDN proxy (PROXY-1).
- **Deprecated aliases (still working, marked `@Deprecated` in OpenAPI):**
  - `POST /api/v1/providers/decode` → `POST /api/v1/sources/{provider}/decode`
  - `GET /api/v1/providers/jutsu/stream` → `GET /api/v1/sources/jutsu/stream`
  - `GET /api/v1/sources/{contentId}/{s}/{e}` → `/api/v1/anime/.../sources`
- **Demo UI**: rewired to canonical paths; new `/sources` view (provider
  capabilities tab + per-source decode sandbox + Play / Download buttons).
- **Docs**: new `docs-site/.../api/sources.md` (+ Russian stub),
  `api/overview.md` extended with the API-tier matrix, OpenAPI snapshot
  regenerated.

### Step 2 — `jutsu-sdk` standalone module

- **New module** `jutsu-sdk/` under the reactor build with public package
  `com.orinuno.jutsu.*`:
  - `JutsuClient` facade + `JutsuConfig` immutable record + `JutsuDecodeResult`
    + `JutsuErrorCodes`.
  - `auth/JutsuSessionManager` — DLE login + sticky cookie jar with TTL +
    re-login on `JUTSU_PREMIUM_REQUIRED`.
  - `decoder/JutsuDecoder` — episode page fetcher + `tab_need_plus` premium
    gate detection + Yandex CDN URL extractor with multi-quality support.
  - `parser/JutsuSourceParser` — pure URL-shape parser.
  - `ratelimit/JutsuRateLimiter` — Bucket4j-backed 1 RPS hard cap, hot-swappable
    via `DoubleSupplier`.
- Decoupled from `OrinunoProperties` and from Spring annotations. Only
  transitive dependencies: `spring-webflux`, `spring-context`, `reactor-netty`,
  `bucket4j`, `micrometer-core`, `jakarta.annotation`.
- **orinuno-app** gets `JutsuSdkConfiguration` that translates
  `OrinunoProperties.JutsuProperties` into `JutsuConfig` and registers the SDK
  beans, reusing the same `JutsuRateLimiter` and `JutsuSessionManager` across
  the `JutsuClient` and the proxy controller (avoids double-RPS).
- 36 SDK-pure unit tests + 1 adapter test + 650 orinuno-app regression tests
  green; live KZ smoke verified premium decode (4 qualities) and proxy stream
  (HTTP 206 Partial Content).

### Step 3 — `sibnet-sdk` + `aniboom-sdk` standalone modules

- **New modules** `sibnet-sdk/` and `aniboom-sdk/` follow the same template as
  `jutsu-sdk`, but stripped of auth/rate-limit/session ceremony — both
  providers are stateless.
- **`sibnet-sdk`** (`com.orinuno.sibnet.*`):
  - `SibnetClient` (overloads: `decode(long videoId)`, `decode(String shellUrl)`).
  - `SibnetConfig`, `SibnetDecodeResult`, `SibnetErrorCodes`
    (`SIBNET_FETCH_ERROR`, `SIBNET_PLAYER_REGEX_BREAK`, `SIBNET_INVALID_SRC`,
    `SIBNET_VIDEO_NOT_FOUND`).
  - `decoder/SibnetDecoder` — `shell.php` iframe fetcher + `player.src(...)`
    regex + URL absolutiser. 404 → `SIBNET_VIDEO_NOT_FOUND` (permanent).
  - `parser/SibnetSourceParser` — accepts both `/video<id>.html` and
    `/shell.php?videoid=<id>` shapes.
- **`aniboom-sdk`** (`com.orinuno.aniboom.*`):
  - `AniboomClient` + `AniboomConfig` + `AniboomDecodeResult` +
    `AniboomErrorCodes` (`ANIBOOM_FETCH_ERROR`, `ANIBOOM_DATA_INPUT_MISSING`,
    `ANIBOOM_GEO_BLOCKED`, `ANIBOOM_JSON_PARSE_ERROR`, `ANIBOOM_NO_PLAYLIST`).
  - `decoder/AniboomDecoder` — embed-page fetcher + `<input id="video-data">`
    extractor + HTML-entity decoder + Jackson JSON parser. Returns HLS as
    `auto` and DASH as `dash`.
  - `parser/AniboomSourceParser` — `/embed/<id>` URL-shape parser.
- Same one-line wiring in `orinuno-app` (`SibnetSdkConfiguration`,
  `AniboomSdkConfiguration`); both feed `RotatingUserAgentProvider.stableDesktop()`
  into the SDK config so all providers share one outbound User-Agent.
- 18 SDK-pure tests (10 sibnet, 8 aniboom) + 5 adapter tests + 634 orinuno-app
  regression tests green. Live KZ smoke verified Sibnet `videoid=5046725 →
  real .mp4`; Aniboom error-code path validated.

### Step 4 — Controllers wired directly on SDK facades

- **Removed** the `*DecoderService` adapter shim layer:
  - `orinuno-app/.../service/provider/jutsu/JutsuDecoderService.java`
  - `orinuno-app/.../service/provider/sibnet/SibnetDecoderService.java`
  - `orinuno-app/.../service/provider/aniboom/AniboomDecoderService.java`
- `SourcesController` and `ProvidersController` now inject the SDK facades
  (`JutsuClient`, `SibnetClient`, `AniboomClient`) directly.
- **New** `com.orinuno.service.provider.ProviderDecodeResults` — single static
  helper translating SDK result records (`JutsuDecodeResult`,
  `SibnetDecodeResult`, `AniboomDecodeResult`) into the orinuno-app HTTP-facing
  `ProviderDecodeResult`. Three overloads × 3 lines each. Six pure-function
  unit tests cover both branches per provider.
- Public HTTP contract is unchanged: same paths, same request/response shapes,
  same status codes. OpenAPI snapshot byte-identical before and after Step 4.
- 633 regression tests green (−1 vs Step 3: −7 deleted adapter tests, +6 new
  mapper tests). Live KZ smoke confirmed all five paths (JutSu decode, Sibnet
  decode, Aniboom error path, legacy `/providers/decode` alias, Kodik error
  wrap) return identical bodies to Step 3.

### Step 5 — Final docs and release notes

- **New** `CHANGELOG.md` (this file).
- **Updated** `README.md` with the multi-module map and per-SDK links.
- **Updated** `docs-site/.../development/project-structure.md` with the full
  4-SDK + orinuno-app reactor layout.
- **Updated** `BACKLOG.md`: `IDEA-SDK-2/3/4` re-classified — `IDEA-SDK-2/3` are
  superseded by the per-source SDK approach (we ended up extracting per-provider
  rather than per-package); `IDEA-SDK-4` (Maven Central publishing) is now
  unblocked and tracked as the natural next epic.
- **Updated** `AGENTS.md` to reflect the final module structure.

### Migration guide for downstream consumers

If you import `com.orinuno.service.provider.{jutsu,sibnet,aniboom}.*` from
orinuno-app, switch to the SDK packages:

| Before (deleted) | After |
|------------------|-------|
| `com.orinuno.service.provider.jutsu.JutsuDecoderService` | `com.orinuno.jutsu.JutsuClient` |
| `com.orinuno.service.provider.sibnet.SibnetDecoderService` | `com.orinuno.sibnet.SibnetClient` |
| `com.orinuno.service.provider.aniboom.AniboomDecoderService` | `com.orinuno.aniboom.AniboomClient` |
| `com.orinuno.service.provider.sibnet.SibnetSourceParser` | `com.orinuno.sibnet.parser.SibnetSourceParser` |
| `com.orinuno.service.provider.aniboom.AniboomSourceParser` | `com.orinuno.aniboom.parser.AniboomSourceParser` |

The HTTP API surface is unchanged. Demo UI / external HTTP consumers don't need
to change anything.

### ADRs

- [ADR 0012 — JutSu SDK extraction](docs/adr/0012-jutsu-sdk-extraction.md)
- [ADR 0013 — Sibnet & Aniboom SDK extraction](docs/adr/0013-sibnet-and-aniboom-sdk-extraction.md)
- [ADR 0014 — Controllers on SDK facades](docs/adr/0014-controllers-on-sdk-facades.md)
- [ADR 0001 (updated) — Kodik SDK extraction follow-ups](docs/adr/0001-kodik-sdk-extraction.md)

### Decisions made along the way

- **`kodik-sdk` extraction is not pursued.** `kodik-sdk-drift` is a
  domain-neutral schema-drift detector, not a Kodik client. The actual Kodik
  HTTP client stays in `orinuno-app` because of its deep MyBatis/MySQL coupling
  with the catalog ingestion pipeline.
- **Shared SPI module rejected.** The four shape-identical `*DecodeResult`
  records (one per SDK + one in orinuno-app) are intentionally duplicated. The
  alternative — a shared `kodik-sdk-spi` module that every SDK depends on — was
  considered and rejected to keep each SDK fully standalone (M3 design).
- **API design A + C.** Adopted both the "thin per-source layer"
  (`/api/v1/sources/{provider}/...`) and the "resource-first" layout
  (`/api/v1/anime/{kinopoiskId}/episodes/{s}/{e}/sources`).

## Earlier work

The pre-SDK-split history is captured in `BACKLOG.md` and the per-area docs.
Highlights:

- **2026-04-26** — Phase 2: async parse-requests + Kodik /list proxy +
  ContentExportDto v2.
- **2026-04-30** — DECODE-8 (Playwright network-sniff fallback decoder),
  DOWNLOAD-PARALLEL (HLS master playlist + retry policy + ffmpeg remux),
  PLAYER-1..5 (multi-provider schema, Sibnet/Aniboom/JutSu/Shikimori),
  AP-7 (multi-source orchestration), META-1 (metadata enrichment),
  KB-1 (operator runbooks).
- **2026-05-02** — IDEA-SDK-1 (`kodik-sdk-drift` extraction pilot),
  IDEA-AP-6 (`/api/v1/embed`), PROXY-1 (JutSu CDN session-bound URL proxy +
  in-browser download with progress).
