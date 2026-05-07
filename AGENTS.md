# AGENTS.md — AI Agent Instructions

This file provides context for AI coding agents (Claude Code, Cursor, Copilot, etc.).
**Read this file and `.cursor/rules/` before starting any work.**

## Project

**orinuno** — standalone open-source service for parsing video content from Kodik.
Spring Boot 3.4.6 + WebFlux + MyBatis + MySQL + Liquibase.

## Quick Reference

> Multi-module reactor since PR3 (transparency roadmap). The Spring Boot
> service lives under `orinuno-app/`; per-source SDK modules live under
> `kodik-sdk-drift/` (PR3 — drift detector), `jutsu-sdk/` (Step 2),
> `sibnet-sdk/` and `aniboom-sdk/` (Step 3). Step 4 wired the
> orinuno-app controllers directly onto the SDK facades and dropped
> the `*DecoderService` adapter shim. Step 5 (ADR 0015) extended the
> jut.su SDK with full browser parity (catalog / search / anime info /
> episode meta / notice feed) plus a drift detector that auto-demotes
> jut.su in `MultiSourceRanker` when upstream HTML changes. ADR 0016
> fixes the architecture trajectory: stay as a modular monolith with a
> universal canonical catalog (L3) as a separate bounded context;
> split into per-source services only when explicit triggers fire. See
> `docs/adr/0001-kodik-sdk-extraction.md`,
> `docs/adr/0012-jutsu-sdk-extraction.md`,
> `docs/adr/0013-sibnet-and-aniboom-sdk-extraction.md`,
> `docs/adr/0014-controllers-on-sdk-facades.md`,
> `docs/adr/0015-jutsu-full-browser-parity.md`, and
> `docs/adr/0016-architecture-trajectory.md`.

| Area | Path |
|------|------|
| Application entry | `orinuno-app/src/main/java/com/orinuno/OrinunoApplication.java` |
| Controllers | `orinuno-app/src/main/java/com/orinuno/controller/` |
| Services | `orinuno-app/src/main/java/com/orinuno/service/` |
| Repositories (MyBatis) | `orinuno-app/src/main/java/com/orinuno/repository/` |
| XML mappers | `orinuno-app/src/main/resources/com/orinuno/db/mapper/` |
| Liquibase migrations | `orinuno-app/src/main/resources/com/orinuno/db/changelog/` |
| Configuration | `orinuno-app/src/main/java/com/orinuno/configuration/` |
| DTOs | `orinuno-app/src/main/java/com/orinuno/model/dto/` |
| Entities | `orinuno-app/src/main/java/com/orinuno/model/` |
| Kodik API client | `orinuno-app/src/main/java/com/orinuno/client/` |
| Kodik token registry | `orinuno-app/src/main/java/com/orinuno/token/` |
| Mappers (entity↔dto) | `orinuno-app/src/main/java/com/orinuno/mapper/` |
| jut.su L1 sync worker (P1a) | `orinuno-app/src/main/java/com/orinuno/jutsu/sync/JutsuCatalogSyncService.java` |
| jut.su L1 lock service (P1a) | `orinuno-app/src/main/java/com/orinuno/jutsu/sync/JutsuNoticeLockService.java` |
| jut.su L1 staleness tracker (P1a) | `orinuno-app/src/main/java/com/orinuno/jutsu/sync/JutsuStalenessTracker.java` |
| jut.su live-fallback (P1a) | `orinuno-app/src/main/java/com/orinuno/jutsu/fallback/JutsuLiveFallbackService.java` |
| jut.su live-fallback wiring (P1a) | `orinuno-app/src/main/java/com/orinuno/jutsu/configuration/JutsuLiveFallbackConfiguration.java` |
| jut.su REST controller (P1a) | `orinuno-app/src/main/java/com/orinuno/controller/JutsuApiController.java` |
| Schema-drift SDK (extracted) | `kodik-sdk-drift/src/main/java/com/kodik/sdk/drift/` |
| JutSu SDK (extracted, Step 2) | `jutsu-sdk/src/main/java/com/orinuno/jutsu/` |
| Sibnet SDK (extracted, Step 3) | `sibnet-sdk/src/main/java/com/orinuno/sibnet/` |
| Aniboom SDK (extracted, Step 3) | `aniboom-sdk/src/main/java/com/orinuno/aniboom/` |
| JutSu Spring wiring | `orinuno-app/src/main/java/com/orinuno/configuration/JutsuSdkConfiguration.java` |
| Sibnet Spring wiring | `orinuno-app/src/main/java/com/orinuno/configuration/SibnetSdkConfiguration.java` |
| Aniboom Spring wiring | `orinuno-app/src/main/java/com/orinuno/configuration/AniboomSdkConfiguration.java` |
| Tests (service) | `orinuno-app/src/test/java/com/orinuno/` |
| Tests (drift SDK) | `kodik-sdk-drift/src/test/java/com/kodik/sdk/drift/` |
| Tests (jutsu SDK) | `jutsu-sdk/src/test/java/com/orinuno/jutsu/` |
| Tests (sibnet SDK) | `sibnet-sdk/src/test/java/com/orinuno/sibnet/` |
| Tests (aniboom SDK) | `aniboom-sdk/src/test/java/com/orinuno/aniboom/` |
| Properties | `orinuno-app/src/main/resources/application.yml` |
| Test properties | `orinuno-app/src/test/resources/application-test.yml` |
| Reactor pom | `pom.xml` |
| Service module pom | `orinuno-app/pom.xml` |
| SDK pilot module pom | `kodik-sdk-drift/pom.xml` |
| JutSu SDK module pom | `jutsu-sdk/pom.xml` |
| Sibnet SDK module pom | `sibnet-sdk/pom.xml` |
| Aniboom SDK module pom | `aniboom-sdk/pom.xml` |
| Docker | `Dockerfile`, `docker-compose.yml` |
| Tech debt tracker | `TECH_DEBT.md` |
| Backlog & ideas | `BACKLOG.md` |
| ADRs | `docs/adr/` |

## Architecture Overview

```
Controller → Service → Repository (MyBatis XML) → MySQL
              ↓
         KodikApiClient → kodik-api.com
              ↓
         KodikVideoDecoderService (ROT13 + Base64 decode)
```

### Key Flows

1. **Search & Parse (sync)**: `ParseController.search()` → `ParserService.search()` → calls Kodik API → saves `KodikContent` + `KodikEpisodeVariant` to DB. Used by demo site / human exploration.
2. **Search & Parse (async, Phase 2)**: `ParseRequestController.submit()` → `ParseRequestService.submit()` (idempotent SHA-256) → row in `orinuno_parse_request` (`status=PENDING/RUNNING/DONE/FAILED`, `phase=QUEUED/SEARCHING/DECODING/DONE/FAILED`) → picked up by `RequestWorker` (`@Scheduled(2s)`, `FOR UPDATE SKIP LOCKED` via `ParseRequestQueueService`) → `ParserService.searchInternal()` with `ThrottledProgressReporter` → `recoverStale` (`@Scheduled(60s)`) handles crashed workers. Used by kodik-parser discovery.
3. **Decode**: `ParseController.decode()` → `ParserService.decodeForContent()` (whole content) or `ParserService.decodeForVariant()` (single variant, behind `POST /api/v1/parse/decode/variant/{variantId}`) → `KodikVideoDecoderService.decode()` → updates `mp4_link` in DB
4. **Export**: `ExportController.getReadyForExport()` → `ExportDataService` → returns `ContentExportDto` (seasons → episodes → variants). Includes Phase 2 fields: `lastSeason/lastEpisode/episodesCount/animeStatus/dramaStatus/allStatus/ongoing`.
5. **Kodik /list proxy (Phase 2)**: `KodikListController.list()` → `KodikListProxyService` → `KodikApiClient.listRaw()`. Adds `Warning: 199` header if drift was observed during the call.
6. **Embed-link shortcut (IDEA-AP-6)**: `KodikEmbedController.resolve()` → `KodikEmbedService.resolve()` → `KodikEmbedHttpClient.getPlayerRaw()` → Kodik `GET /get-player`. Returns a single `EmbedLinkDto` for the supplied external id (`shikimori`, `kinopoisk`, `imdb`, `mdl`, `kodik`, `worldart_animation`, `worldart_cinema`) without writing to the DB or triggering the decoder. Use this when you just need an iframe URL; use `/parse/search` when you also need to ingest.
7. **TTL Refresh**: `@Scheduled ParserService.refreshExpiredLinks()` → re-decodes links older than TTL
8. **Retry Failed**: `@Scheduled ParserService.retryFailedDecodes()` → retries previously failed decodes
9. **jut.su browser parity (ADR 0015)**: `JutsuApiController` under `/api/v1/sources/jutsu/` calls the `jutsu-sdk` facade directly — `/catalog`, `/search`, `/anime/{slug}`, `/episode`, `/notice`, `/notice/stream` (NDJSON), `/drift`. `JutsuDriftScheduledProbe` (`@Scheduled`, `@ConditionalOnProperty`) hits a canary set in lenient mode; `MultiSourceController` reads `JutsuClient.getDriftSnapshot().health()` and adds jut.su to `RankingPreferences.demotedProviders` whenever health ≠ HEALTHY (it still appears in results, but lands at the bottom).
10. **jut.su L1 cache + hybrid fallback (ADR 0016 P1a)**: `JutsuCatalogSyncService` (`@Scheduled`) runs a full crawl every `JUTSU_SYNC_FULL_CRAWL_INTERVAL_HOURS` (default 48h) and an incremental notice walk every `JUTSU_SYNC_NOTICE_INTERVAL_MINUTES` (default 5m); the singleton `jutsu_sync_state` row is locked through the dedicated `JutsuNoticeLockService` (separate `@Service` to dodge `@Transactional` self-invocation) using an atomic acquire-or-recover update gated by `JUTSU_SYNC_NOTICE_LOCK_TTL_MINUTES` (default 30m, recovers locks left by crashed workers). `JutsuApiController` reads `/catalog`, `/search`, `/anime/{slug}`, `/episode` DB-first; cache misses (and explicit `?refresh=true`) go through `JutsuLiveFallbackService` — Bucket4j RPS limit (`JUTSU_LIVE_FALLBACK_RPS`, default 0.2 rps), Caffeine-bounded buckets (`JUTSU_LIVE_FALLBACK_BUCKETS_*`), Caffeine negative cache (`JUTSU_LIVE_FALLBACK_NEGATIVE_CACHE_TTL_HOURS`, default 24h, populated only on 4xx/null upstream — 5xx/IO/timeout/`JutsuDriftException` surface as 502 with the `UPSTREAM_ERROR` outcome), and a kill-switch (`JUTSU_LIVE_FALLBACK_ENABLED`, default `false` in `JutsuLiveFallbackProperties` and overridden to `true` in dev `application.yml`). Successful fallback writes are upserted into `jutsu_title` / `jutsu_episode` so the next caller hits the cache. Every response carries `X-Sync-Stale-Seconds` derived from `JutsuStalenessTracker` (Caffeine, 30s TTL, invalidated by `JutsuCatalogSyncService.fullCrawl()`). The controller is fully reactive — DB reads run on `Schedulers.boundedElastic()` via `Mono.fromCallable(...)`; SDK calls go through `JutsuLiveFallbackService.dispatchReactive(Supplier<Mono<T>>)` so no `.block()` ever runs on the WebFlux event loop. `notice` and `notice/stream` stay live-only on purpose — that feed *is* the change feed.

### Database Tables

Tables are grouped by bounded context (ADR 0016). Cross-context FK constraints are forbidden; all cross-context references are soft.

| Context | Table | Purpose | Unique key |
|---------|-------|---------|------------|
| `kodik` | `kodik_content` | Content metadata | `kinopoisk_id` |
| `kodik` | `kodik_episode_variant` | Episode/translation variants with mp4 links | `(content_id, season, episode, translation_id)` |
| `kodik` | `kodik_proxy` | Proxy pool | `(host, port)` |
| `kodik` | `kodik_decoder_path_cache` | Persistent cache of decoder POST path per netloc | `netloc` |
| `kodik` | `kodik_calendar_state` | Last calendar snapshot per shikimori_id (CAL-6) | `shikimori_id` |
| `kodik` | `kodik_calendar_outbox` | Calendar deltas with watermark | auto-incrementing seq |
| `kodik` | `kodik_content_enrichment` | Raw + enriched metadata (Shikimori/MAL/Kinopoisk) (META-1) | `kodik_content_id` |
| `jutsu` | `jutsu_title` (P1a) | jut.su catalog mirror | `slug` |
| `jutsu` | `jutsu_episode` (P1a) | jut.su episode metadata + qualities | `(title_slug, season, episode)` |
| `jutsu` | `jutsu_sync_state` (P1a) | sync cursor (full crawl + notice feed) | singleton |
| `catalog` | `catalog_content` (P1b) | Universal canonical record | `id` |
| `catalog` | `catalog_content_external_id` (P1b) | source-typed external IDs | `(source_type, external_id)` |
| `catalog` | `catalog_episode` (P1b) | Canonical episodes | `(content_id, season, episode)` |
| `catalog` | `catalog_episode_source_link` (P1b) | M:N link canonical → per-source `episode_source` | `(catalog_episode_id, episode_source_id)` |
| `core` | `orinuno_parse_request` | Async parse-request log (Phase 2) | `request_hash` (active rows only) |
| `core` | `orinuno_dump_state` | Public Kodik dump poll state | `dump_kind` |
| `core` | `episode_source` | provider-agnostic source-per-episode (ADR 0005) | `(content_id, season, episode, source_type, source_id)` |
| `core` | `episode_video` | decoded URLs per quality with TTL (ADR 0005) | `(episode_source_id, quality)` |

### Video Decoding

Kodik uses a custom obfuscation: ROT13 with shift +18 (mod 26) + URL-safe Base64 encoding. The `KodikVideoDecoderService` handles the 8-step decoding process: fetch iframe → extract JS params → build POST request → get encoded URL → ROT13 decode → Base64 decode → get final mp4 URLs.

## Bounded contexts (ADR 0016)

`orinuno-app` is one process, but logically split into bounded contexts. Code lives under `com.orinuno.<context>` packages; each context owns a Liquibase changelog directory (`com/orinuno/db/changelog/<context>/`) and exposes its public surface via a single `*PublicApi` interface.

| Context | Package | Owns (DB tables) | Class |
|---------|---------|------------------|-------|
| `kodik` | `com.orinuno.{client,token,service.calendar,service.requestlog,service.metrics}` (existing pre-ADR-0016 layout) | `kodik_content`, `kodik_episode_variant`, `kodik_proxy`, `kodik_decoder_path_cache`, `kodik_calendar_state`, `kodik_calendar_outbox`, `kodik_content_enrichment` | catalog source |
| `jutsu` | `com.orinuno.jutsu` (orinuno-app side, separate from `jutsu-sdk`) | `jutsu_title`, `jutsu_episode`, `jutsu_translation` (optional), `jutsu_sync_state` (P1a) | catalog source |
| `aniboom` | `com.orinuno.aniboom` (thin Spring wiring around `aniboom-sdk`) | — | decoder source (stateless) |
| `sibnet` | `com.orinuno.sibnet` (thin Spring wiring around `sibnet-sdk`) | — | decoder source (stateless) |
| `catalog` | `com.orinuno.catalog` (NEW, P1b) | `catalog_content`, `catalog_content_external_id`, `catalog_episode`, `catalog_episode_source_link` | universal canonical catalog |
| `core` | `com.orinuno.{core,service.requestlog}` | `orinuno_parse_request`, `orinuno_dump_state`, `episode_source`, `episode_video` | cross-source orchestration |

### Zoning rules (enforced by ArchUnit + Liquibase guard tests in P3)

- **No cross-context `@Autowired`** of internal classes. The only types crossing a context boundary are the context's `*PublicApi` interface and DTOs in the context's `api` subpackage. Everything else is package-local.
- **No cross-context `FOREIGN KEY` constraints** in the database. Cross-context references are soft (raw column with the other context's PK value, no FK). This makes a future per-source service split (ADR 0016 Layout B) a refactor instead of a rewrite.
- **Each context owns its Liquibase changelog directory**. `liquibase-changelog.yaml` aggregates them via explicit `<include>` per directory.
- **SDK facade stability**. Each SDK's facade (`KodikApiClient`, `JutsuClient`, `SibnetClient`, `AniboomClient`) plus its result records are the only types crossing the SDK boundary. They are the wire types if/when we ever distribute the system.

### Adding a new source

The decision tree from ADR 0016 §"Source classification":

1. **"Does the source expose a list of titles?"** → catalog source → add an L1 schema (`<source>_title`, `<source>_episode`, `<source>_sync_state` at minimum) and a sync worker (full crawl + incremental). Wire it through `CatalogIngestionService` so titles get reflected into L3 (`catalog_content`).
2. **"Does the source take a URL and return mp4?"** → decoder source → keep it stateless behind the SDK facade. No DB tables, no sync worker. Aniboom and Sibnet are the canonical examples.
3. A source that is **both** (Kodik, future Sibnet-with-album-listing) gets both: L1 catalog tables + the decoder pipeline.

## Key Rules

- **Open-source standalone**: No dependencies on any private backend project. No company-specific references, tokens, or imports.
- **Kodik API domain**: `kodik-api.com` (with hyphen). NOT `kodikapi.com`.
- **Kodik tokens**: Managed by `KodikTokenRegistry` over `data/kodik_tokens.json` (gitignored). Tier model + `functions_availability` matrix mirror AnimeParsers' `kdk_tokns/tokens.json`. Full contract in `data/TOKENS.md`. Never commit real token values. First boot seeds from `KODIK_TOKEN` env, or scrapes `kodik-add.com/add-players.min.js` as a legacy fallback. **DEAD-tier is not terminal**: `validateAll()` re-probes dead entries every `orinuno.kodik.dead-revalidation-interval-minutes` (default 24h) and `markValid()` auto-promotes them back to `unstable` on first success — see BACKLOG `TD-TOKEN-1`.
- **COALESCE upsert**: When upserting `kodik_episode_variant`, never overwrite a valid `mp4_link` with NULL. Use `COALESCE(VALUES(mp4_link), mp4_link)`.
- **SQL injection protection**: `sortBy` and `order` parameters in `ContentController` are whitelisted. MyBatis `${...}` interpolation is used only for these validated fields.
- **API key auth**: When `orinuno.security.api-key` is set, all `/api/v1/content`, `/api/v1/parse` (incl. `/parse/requests`), `/api/v1/export`, `/api/v1/download`, `/api/v1/kodik`, `/api/v1/calendar`, `/api/v1/embed`, `/api/v1/sources/jutsu/**` require `X-API-KEY` header.
- **jut.su drift modes (ADR 0015)**: SDK parsers run in **lenient** mode by default — schema drift is logged + counted, parsing continues best-effort. **Strict** mode (`JutsuParserContext.strict()`) is reserved for `JutsuStrictReplayTest` against captured fixtures. Never flip production calls to strict; instead add a fixture and let strict-mode replay catch the regression.
- **jut.su L1 read path (ADR 0016 P1a)**: never call `JutsuClient` directly from a request-time controller. Always go through `JutsuLiveFallbackService.dispatchReactive(...)` so the rate limiter, negative cache, and kill-switch apply. New endpoints that need DB-first reads must add a `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())` branch and stamp `X-Sync-Stale-Seconds` via `JutsuStalenessTracker`. Production sync work goes via `JutsuNoticeLockService` (lock acquisition is `@Transactional`; `JutsuCatalogSyncService` MUST NOT mark its own methods `@Transactional` — Spring AOP self-invocation will silently no-op the lock).
- **No-polling rule for parse-requests**: machine consumers (kodik-parser) MUST drive completion via `GET /api/v1/export/ready?updatedSince=…`, not by polling `GET /api/v1/parse/requests/{id}`. The list endpoint is allowed for backpressure (`?status=PENDING&limit=0` → `X-Total-Count`) only.
- **Retry with backoff**: Decoder uses `Retry.backoff(maxRetries, 2s)` — do not remove retry logic.
- **TTL links**: mp4 links from Kodik CDN expire. `mp4_link_decoded_at` tracks when a link was decoded. Scheduled task refreshes expired links.

## Development

```bash
# Docker compose (MySQL + app) — Dockerfile builds the multi-module reactor
cp .env.example .env   # set KODIK_TOKEN
docker compose up -d

# Manual run (spring-boot:run lives in the orinuno-app submodule)
mvn -pl orinuno-app -am spring-boot:run

# Tests (whole reactor)
mvn test

# Tests (single module)
mvn -pl kodik-sdk-drift test
mvn -pl jutsu-sdk test
mvn -pl sibnet-sdk test
mvn -pl aniboom-sdk test
mvn -pl orinuno-app test

# Live integration test
KODIK_TOKEN=xxx mvn -pl orinuno-app test -Dtest=KodikLiveIntegrationTest
```

## Git

- Never change git config.
- Never commit or push without explicit user permission.
- Never commit files containing real API tokens or secrets.

## Architecture Diagrams

See `ARCHITECTURE.md` for Mermaid diagrams:
- System context / component diagram
- Sequence diagrams for all key flows
- ER diagram
- Video URL decoding pipeline
- Integration guide for consumers

## Backlog & Competitive Context

**Read `BACKLOG.md` before starting feature work.** It contains:
- 23 prioritized tasks (tech debt + ideas from competitive analysis)
- Feature comparison table against 4 reference projects
- Schema drift audit results
- Kodik protection mechanisms

### Reference Projects (read-only, for inspiration)

| Project | Stack | Local path | Key takeaways |
|---------|-------|------------|---------------|
| kodik-api | Rust | `../kodik-api-rust/` | Full REST API coverage, enum types, strict typing |
| kodikwrapper | TypeScript | `../kodikwrapper/` | Dynamic endpoint discovery, token auto-discovery, brute-force ROT |
| AnimeParsers | Python | `../AnimeParsers/` | Multi-source (Kodik + Aniboom + JutSu + Shikimori), fluent API, token auto-discovery |
| KodikDownloader | Android/Java | `../KodikDownloader/` | Mobile MVVM, batch download to ADM/IDM, ROT13 decode |

These are **not our projects** — we study them for feature ideas and gap analysis only.

## Known Tech Debt

See `TECH_DEBT.md` and `BACKLOG.md` for details:
- ~~Async jobs for long-running decode operations (TD-1)~~ — **DONE in Phase 2**, see `ARCHITECTURE.md` §7.
- TD-PR-1: single-thread RequestWorker (Phase 4/5 will introduce a worker pool / fully reactive boundary)
- TD-PR-2: optional dry-write debug for `/list` proxy
- TD-PR-3: `Phase2EndToEndIT` (Testcontainers-based)
- Rate limiter enforcement (TD-2, IDEA-10)
- ParseRequestDto validation (TD-2)
- Schema drift Level 2 — persistent storage (TD-3, IDEA-DRIFT-2)
- Pre-commit hook for spotless / spotbugs (orinuno was the project where the missing hook bit us)
- Multi-source support: Aniboom, JutSu, Shikimori, Sibnet (IDEA-AP-1..4)
