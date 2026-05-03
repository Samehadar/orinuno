# Changelog

All notable changes to this project are documented here. The format is loosely
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the
project follows the SemVer-flavoured `0.x` cycle until the public API
stabilises. The changelog is hand-written; commit messages remain the
authoritative log of every change.

## [Unreleased]

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
