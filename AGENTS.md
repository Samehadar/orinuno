# AGENTS.md — AI Agent Instructions

This file provides context for AI coding agents (Claude Code, Cursor, Copilot, etc.).
**Read this file and `.cursor/rules/` before starting any work.**

## Project

**orinuno** — standalone open-source service for parsing video content from Kodik.
Spring Boot 3.4.6 + WebFlux + MyBatis + MySQL + Liquibase.

## Quick Reference

> Multi-module reactor since PR3 (transparency roadmap). The Spring Boot
> service lives under `orinuno-app/`; the first SDK pilot lives under
> `kodik-sdk-drift/`. See `docs/adr/0001-kodik-sdk-extraction.md`.

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
| Schema-drift SDK (extracted) | `kodik-sdk-drift/src/main/java/com/kodik/sdk/drift/` |
| Tests (service) | `orinuno-app/src/test/java/com/orinuno/` |
| Tests (drift SDK) | `kodik-sdk-drift/src/test/java/com/kodik/sdk/drift/` |
| Properties | `orinuno-app/src/main/resources/application.yml` |
| Test properties | `orinuno-app/src/test/resources/application-test.yml` |
| Reactor pom | `pom.xml` |
| Service module pom | `orinuno-app/pom.xml` |
| SDK pilot module pom | `kodik-sdk-drift/pom.xml` |
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
2. **Search & Parse (async, Phase 2)**: `ParseRequestController.submit()` → `ParseRequestService.submit()` (idempotent SHA-256) → row in `orinuno_parse_request` (`status=PENDING/RUNNING/DONE/FAILED`, `phase=QUEUED/SEARCHING/DECODING/DONE/FAILED`) → picked up by `RequestWorker` (`@Scheduled(2s)`, `FOR UPDATE SKIP LOCKED` via `ParseRequestQueueService`) → `ParserService.searchInternal()` with `ThrottledProgressReporter` → `recoverStale` (`@Scheduled(60s)`) handles crashed workers. Used by parser-kodik discovery.
3. **Decode**: `ParseController.decode()` → `ParserService.decodeForContent()` (whole content) or `ParserService.decodeForVariant()` (single variant, behind `POST /api/v1/parse/decode/variant/{variantId}`) → `KodikVideoDecoderService.decode()` → updates `mp4_link` in DB
4. **Export**: `ExportController.getReadyForExport()` → `ExportDataService` → returns `ContentExportDto` (seasons → episodes → variants). Includes Phase 2 fields: `lastSeason/lastEpisode/episodesCount/animeStatus/dramaStatus/allStatus/ongoing`.
5. **Kodik /list proxy (Phase 2)**: `KodikListController.list()` → `KodikListProxyService` → `KodikApiClient.listRaw()`. Adds `Warning: 199` header if drift was observed during the call.
6. **Embed-link shortcut (IDEA-AP-6)**: `KodikEmbedController.resolve()` → `KodikEmbedService.resolve()` → `KodikEmbedHttpClient.getPlayerRaw()` → Kodik `GET /get-player`. Returns a single `EmbedLinkDto` for the supplied external id (`shikimori`, `kinopoisk`, `imdb`, `mdl`, `kodik`, `worldart_animation`, `worldart_cinema`) without writing to the DB or triggering the decoder. Use this when you just need an iframe URL; use `/parse/search` when you also need to ingest.
7. **TTL Refresh**: `@Scheduled ParserService.refreshExpiredLinks()` → re-decodes links older than TTL
8. **Retry Failed**: `@Scheduled ParserService.retryFailedDecodes()` → retries previously failed decodes

### Database Tables

| Table | Purpose | Unique key |
|-------|---------|------------|
| `kodik_content` | Content metadata | `kinopoisk_id` |
| `kodik_episode_variant` | Episode/translation variants with mp4 links | `(content_id, season, episode, translation_id)` |
| `kodik_proxy` | Proxy pool | `(host, port)` |
| `orinuno_parse_request` | Async parse-request log (Phase 2) | `request_hash` (active rows only) |

### Video Decoding

Kodik uses a custom obfuscation: ROT13 with shift +18 (mod 26) + URL-safe Base64 encoding. The `KodikVideoDecoderService` handles the 8-step decoding process: fetch iframe → extract JS params → build POST request → get encoded URL → ROT13 decode → Base64 decode → get final mp4 URLs.

## Key Rules

- **Open-source standalone**: No dependencies on any private backend project. No company-specific references, tokens, or imports.
- **Kodik API domain**: `kodik-api.com` (with hyphen). NOT `kodikapi.com`.
- **Kodik tokens**: Managed by `KodikTokenRegistry` over `data/kodik_tokens.json` (gitignored). Tier model + `functions_availability` matrix mirror AnimeParsers' `kdk_tokns/tokens.json`. Full contract in `data/TOKENS.md`. Never commit real token values. First boot seeds from `KODIK_TOKEN` env, or scrapes `kodik-add.com/add-players.min.js` as a legacy fallback. **DEAD-tier is not terminal**: `validateAll()` re-probes dead entries every `orinuno.kodik.dead-revalidation-interval-minutes` (default 24h) and `markValid()` auto-promotes them back to `unstable` on first success — see BACKLOG `TD-TOKEN-1`.
- **COALESCE upsert**: When upserting `kodik_episode_variant`, never overwrite a valid `mp4_link` with NULL. Use `COALESCE(VALUES(mp4_link), mp4_link)`.
- **SQL injection protection**: `sortBy` and `order` parameters in `ContentController` are whitelisted. MyBatis `${...}` interpolation is used only for these validated fields.
- **API key auth**: When `orinuno.security.api-key` is set, all `/api/v1/content`, `/api/v1/parse` (incl. `/parse/requests`), `/api/v1/export`, `/api/v1/download`, `/api/v1/kodik`, `/api/v1/calendar`, `/api/v1/embed` require `X-API-KEY` header.
- **No-polling rule for parse-requests**: machine consumers (parser-kodik) MUST drive completion via `GET /api/v1/export/ready?updatedSince=…`, not by polling `GET /api/v1/parse/requests/{id}`. The list endpoint is allowed for backpressure (`?status=PENDING&limit=0` → `X-Total-Count`) only.
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
