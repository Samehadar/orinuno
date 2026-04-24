# AGENTS.md — AI Agent Instructions

This file provides context for AI coding agents (Claude Code, Cursor, Copilot, etc.).
**Read this file and `.cursor/rules/` before starting any work.**

## Project

**orinuno** — standalone open-source service for parsing video content from Kodik.
Spring Boot 3.4.6 + WebFlux + MyBatis + MySQL + Liquibase.

## Quick Reference

| Area | Path |
|------|------|
| Application entry | `src/main/java/com/orinuno/OrinunoApplication.java` |
| Controllers | `src/main/java/com/orinuno/controller/` |
| Services | `src/main/java/com/orinuno/service/` |
| Repositories (MyBatis) | `src/main/java/com/orinuno/repository/` |
| XML mappers | `src/main/resources/com/orinuno/db/mapper/` |
| Liquibase migrations | `src/main/resources/com/orinuno/db/changelog/` |
| Configuration | `src/main/java/com/orinuno/configuration/` |
| DTOs | `src/main/java/com/orinuno/model/dto/` |
| Entities | `src/main/java/com/orinuno/model/` |
| Kodik API client | `src/main/java/com/orinuno/client/` |
| Kodik token registry | `src/main/java/com/orinuno/token/` |
| Mappers (entity↔dto) | `src/main/java/com/orinuno/mapper/` |
| Tests | `src/test/java/com/orinuno/` |
| Properties | `src/main/resources/application.yml` |
| Test properties | `src/test/resources/application-test.yml` |
| Docker | `Dockerfile`, `docker-compose.yml` |
| Tech debt tracker | `TECH_DEBT.md` |
| Backlog & ideas | `BACKLOG.md` |

## Architecture Overview

```
Controller → Service → Repository (MyBatis XML) → MySQL
              ↓
         KodikApiClient → kodik-api.com
              ↓
         KodikVideoDecoderService (ROT13 + Base64 decode)
```

### Key Flows

1. **Search & Parse**: `ParseController.search()` → `ParserService.search()` → calls Kodik API → saves `KodikContent` + `KodikEpisodeVariant` to DB
2. **Decode**: `ParseController.decode()` → `ParserService.decodeForContent()` → `KodikVideoDecoderService.decode()` → updates `mp4_link` in DB
3. **Export**: `ExportController.getReadyForExport()` → `ExportDataService` → returns `ContentExportDto` (seasons → episodes → variants)
4. **TTL Refresh**: `@Scheduled ParserService.refreshExpiredLinks()` → re-decodes links older than TTL
5. **Retry Failed**: `@Scheduled ParserService.retryFailedDecodes()` → retries previously failed decodes

### Database Tables

| Table | Purpose | Unique key |
|-------|---------|------------|
| `kodik_content` | Content metadata | `kinopoisk_id` |
| `kodik_episode_variant` | Episode/translation variants with mp4 links | `(content_id, season, episode, translation_id)` |
| `kodik_proxy` | Proxy pool | `(host, port)` |

### Video Decoding

Kodik uses a custom obfuscation: ROT13 with shift +18 (mod 26) + URL-safe Base64 encoding. The `KodikVideoDecoderService` handles the 8-step decoding process: fetch iframe → extract JS params → build POST request → get encoded URL → ROT13 decode → Base64 decode → get final mp4 URLs.

## Key Rules

- **Open-source standalone**: No dependencies on any private backend project. No company-specific references, tokens, or imports.
- **Kodik API domain**: `kodik-api.com` (with hyphen). NOT `kodikapi.com`.
- **Kodik tokens**: Managed by `KodikTokenRegistry` over `data/kodik_tokens.json` (gitignored). Tier model + `functions_availability` matrix mirror AnimeParsers' `kdk_tokns/tokens.json`. Full contract in `data/TOKENS.md`. Never commit real token values. First boot seeds from `KODIK_TOKEN` env, or scrapes `kodik-add.com/add-players.min.js` as a legacy fallback.
- **COALESCE upsert**: When upserting `kodik_episode_variant`, never overwrite a valid `mp4_link` with NULL. Use `COALESCE(VALUES(mp4_link), mp4_link)`.
- **SQL injection protection**: `sortBy` and `order` parameters in `ContentController` are whitelisted. MyBatis `${...}` interpolation is used only for these validated fields.
- **API key auth**: When `orinuno.security.api-key` is set, all `/api/v1/content`, `/api/v1/parse`, `/api/v1/export` require `X-API-KEY` header.
- **Retry with backoff**: Decoder uses `Retry.backoff(maxRetries, 2s)` — do not remove retry logic.
- **TTL links**: mp4 links from Kodik CDN expire. `mp4_link_decoded_at` tracks when a link was decoded. Scheduled task refreshes expired links.

## Development

```bash
# Docker compose (MySQL + app)
cp .env.example .env   # set KODIK_TOKEN
docker compose up -d

# Manual
mvn spring-boot:run

# Tests
mvn test

# Live integration test
KODIK_TOKEN=xxx mvn test -Dtest=KodikLiveIntegrationTest
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
- Async jobs for long-running decode operations (TD-1)
- Rate limiter enforcement (TD-2, IDEA-10)
- ParseRequestDto validation (TD-2)
- Schema drift Level 2 — persistent storage (TD-3)
- Multi-source support: Aniboom, JutSu, Shikimori, Sibnet (IDEA-AP-1..4)
