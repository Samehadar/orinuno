# AGENTS.md — AI Agent Instructions

This file provides context for AI coding agents (Claude Code, Cursor, Copilot, etc.).
**Read this file and `.cursor/rules/` before starting any work.**

## Project

**orinuno** — standalone open-source service for parsing video content from Kodik / jut.su / Sibnet / Aniboom.
Spring Boot 3.5.x + WebFlux + MySQL + Liquibase. Reactor split per ADR 0018 → ADR 0021.

## Architecture in 90 seconds

The reactor is a per-source-service split. orinuno-app is a thin gateway +
cross-source orchestrator; the actual Kodik / jut.su write-paths live in
`orinuno-source-kodik` / `orinuno-source-jutsu`; the canonical catalog
(L2 + L3) is owned end-to-end by `meter`. Cross-source communication is
HTTP (per-source `*UpstreamProxyFilter` in orinuno-app) and a
`SourceCatalogEvent` outbox served at `/api/v1/source-events/ready` on
every per-source service. orinuno-app reads catalog over a read-only DS
qualified `catalogReadJdbcTemplate`.

ADR lineage:
- 0016 — bounded-context discipline (catalog vs. per-source, no cross-context FK)
- 0017 — producer-side `SourceCatalogEvent` contract, `orinuno-source-contract` artifact
- 0018 — per-source service extraction template (Kodik path = Phase 2; meter L2/L3 cutover = Phase 5)
- 0019 — same template applied to jut.su
- 0020 — meter as single L2/L3 writer; DB user separation; orinuno-app holds zero writes
- 0021 — Phase 2/5 write-path cleanup: closed the gap between "code moved" and "orinuno-app stopped owning the slice". Tracker in `docs/adr/0021-phase-2-5-write-path-cleanup.md`.

Module-by-module ownership (see root `README.md` for the full table):

| Module | Owns | Surface |
|---|---|---|
| `orinuno-app` | gateway + cross-source orchestration | `MultiSourceController`, `CatalogController` (read-only over `catalogReadJdbcTemplate`), `SourcesController`, `ProvidersController`, demo UI; reverse-proxies `/api/v1/parse/`, `/api/v1/stream/`, `/api/v1/hls/`, `/api/v1/download/`, `/api/v1/export/`, `/api/v1/calendar/`, `/api/v1/embed/`, `/api/v1/reference/`, `/api/v1/sources/jutsu/*` to per-source services |
| `orinuno-source-kodik` | `orinuno_source_kodik` L1 schema (kodik_*, parse-request queue, decoder cache, calendar) | every Kodik route enumerated above + `/api/v1/source-events/ready` |
| `orinuno-source-jutsu` | `orinuno_source_jutsu` L1 schema (jutsu_title / jutsu_episode / jutsu_film / jutsu_sync_state) | `/api/v1/sources/jutsu/*` + `/api/v1/source-events/ready` |
| `meter` | `orinuno_catalog` schema (catalog_* + episode_source + episode_video) | consumes `/api/v1/source-events/ready`; exposes nothing publicly today (read happens over the shared DB via `catalogReadJdbcTemplate`) |
| `orinuno-source-contract` | `SourceCatalogEvent` sealed family | Maven artifact, Spring-free, Kin-free |
| `kodik-sdk` | Kodik HTTP / decoder / token / drift | Spring-free (WebFlux only) |
| `kodik-sdk-spring-boot-starter` | auto-config glue for kodik-sdk | wires `KodikApiClient` + token registry into any Boot host |
| `jutsu-sdk` / `sibnet-sdk` / `aniboom-sdk` | per-source decoders | Spring-free |

## Quick reference — where is X?

Most slices that used to live in `orinuno-app/src/main/java/com/orinuno/{client,token,service,model}/`
moved to per-source services. Cross-check `docs/adr/0021-phase-2-5-write-path-cleanup.md`
for the exact commit lineage; when in doubt, `git log --all --oneline -- <path>`
is authoritative.

| Concern | Module | Path |
|---|---|---|
| Gateway entry point | orinuno-app | `orinuno-app/src/main/java/com/orinuno/OrinunoApplication.java` |
| Reverse-proxy filters | orinuno-app | `orinuno-app/src/main/java/com/orinuno/configuration/{Kodik,Jutsu}UpstreamProxyFilter.java` |
| Cross-source ranker | orinuno-app | `orinuno-app/src/main/java/com/orinuno/service/orchestration/MultiSourceRanker.java` |
| Catalog read repos (over `catalogReadJdbcTemplate`) | orinuno-app | `orinuno-app/src/main/java/com/orinuno/catalog/readonly/` |
| Read-only catalog DS config | orinuno-app | `orinuno-app/src/main/java/com/orinuno/catalog/readonly/CatalogReadDataSourceConfiguration.java` |
| Cross-source `MultiSourceController` | orinuno-app | `orinuno-app/src/main/java/com/orinuno/controller/MultiSourceController.java` |
| jut.su drift canary probe | orinuno-app | `orinuno-app/src/main/java/com/orinuno/service/jutsu/JutsuDriftScheduledProbe.java` (feeds the SDK drift detector that `MultiSourceRanker` reads to demote jut.su when health ≠ HEALTHY) |
| jut.su full catalog/decoder slice (sync workers, cache-first reads, live fallback, circuit breaker, negative cache) | orinuno-source-jutsu | `orinuno-source-jutsu/src/main/java/com/orinuno/source/jutsu/` |
| ArchUnit + Liquibase guards | orinuno-app | `orinuno-app/src/test/java/com/orinuno/architecture/` |
| Kodik L1 + decoder + parse queue + calendar | orinuno-source-kodik | `orinuno-source-kodik/src/main/java/com/orinuno/source/kodik/` |
| jut.su L1 + sync workers + drift probe | orinuno-source-jutsu | `orinuno-source-jutsu/src/main/java/com/orinuno/source/jutsu/` |
| L2 + L3 catalog writers + identity resolver | meter | `meter/src/main/java/com/orinuno/meter/` |
| `SourceCatalogEvent` sealed family | orinuno-source-contract | `orinuno-source-contract/src/main/java/com/orinuno/contract/source/` |
| Kodik SDK (HTTP + token + decoder + drift) | kodik-sdk | `kodik-sdk/src/main/java/com/kodik/` |
| Spring auto-config for kodik-sdk | kodik-sdk-spring-boot-starter | `kodik-sdk-spring-boot-starter/src/main/java/com/kodik/sdk/spring/` |
| jut.su / Sibnet / Aniboom SDKs | jutsu-sdk / sibnet-sdk / aniboom-sdk | `<module>/src/main/java/com/orinuno/<source>/` |

### Properties — who reads what?

After ADR 0021 E2 stage 3b, the `orinuno.*` namespace in orinuno-app's
`application.yml` is intentionally minimal. Other prefixes belong to
other modules:

| Prefix | Owner | Notes |
|---|---|---|
| `orinuno.parse.*` | orinuno-app (`ParseInboundRateLimiter`) | Inbound rate limit on `POST /api/v1/parse/requests`. |
| `orinuno.security.api-key` | orinuno-app (`ApiKeyAuthFilter`) | Gateway-wide auth. |
| `orinuno.cors.allowed-origins` | orinuno-app (`WebConfiguration`) | CORS for browser clients / demo UI. |
| `orinuno.drift.*` | orinuno-app (`DriftDetectorConfig`) | Shared drift sampling knobs. |
| `orinuno.providers.jutsu.*` | orinuno-app (`JutsuSdkConfiguration` + `JutsuDriftScheduledProbe`) | DLE auth + drift probe canary. The `sync` / `fallback` sub-trees are consumed by orinuno-source-jutsu. |
| `orinuno.source-kodik.base-url` | orinuno-app (`KodikUpstreamProxyFilter`) | Where to reverse-proxy Kodik routes. |
| `orinuno.source-jutsu.base-url` | orinuno-app (`JutsuUpstreamProxyFilter`) | Where to reverse-proxy jut.su routes. |
| `orinuno.catalog-read.{url,username,password,driver-class-name}` | orinuno-app (`CatalogReadDataSourceConfiguration`) | Wires `catalogReadJdbcTemplate`. Unset → read-side controllers stay 404. |
| `orinuno.source-kodik.*` | orinuno-source-kodik | Decoder / parse queue / calendar / Playwright / storage / requests. Same prefix as the per-source service. |
| `kodik.sdk.*` | kodik-sdk-spring-boot-starter | Token file path, validation interval, failover attempts, etc. Read in any Boot host that wires the starter. |

If a slice's properties live somewhere unexpected, that slice has not
finished its ADR 0021 ride-along — flag it on the tracker before
adding new keys.

## Cross-cutting key rules

- **Open-source standalone**: No dependencies on any private backend project. No company-specific references, tokens, or imports.
- **Kodik API domain**: `kodik-api.com` (with hyphen). NOT `kodikapi.com`.
- **Kodik tokens**: Managed by `kodik-sdk`'s `KodikTokenRegistry` over `data/kodik_tokens.json` (gitignored). Tier model + `functions_availability` matrix mirror AnimeParsers' `kdk_tokns/tokens.json`. Full contract in `data/TOKENS.md`. Never commit real token values. First boot seeds from `KODIK_TOKEN` env, or scrapes `kodik-add.com/add-players.min.js` as a legacy fallback. **DEAD-tier is not terminal**: `validateAll()` re-probes dead entries every `kodik.sdk.token.dead-revalidation-interval-minutes` (default 24h) and `markValid()` auto-promotes them back to `unstable` on first success.
- **No-polling rule for parse-requests**: machine consumers (kodik-parser) MUST drive completion via the `SourceCatalogEvent` stream at `/api/v1/source-events/ready?updatedSince=…` on the per-source service that emitted the events, not by polling per-request endpoints.
- **jut.su drift modes (ADR 0015)**: SDK parsers run in **lenient** mode by default — schema drift is logged + counted, parsing continues best-effort. **Strict** mode (`JutsuParserContext.strict()`) is reserved for replay tests against captured fixtures. Never flip production calls to strict; instead add a fixture and let strict-mode replay catch the regression.
- **Retry with backoff**: Kodik decoder uses `Retry.backoff(maxRetries, 2s)`. Lives in `orinuno-source-kodik`. Do not strip retry from the SDK or the orchestrator.
- **TTL links**: mp4 links from Kodik CDN expire. `episode_video.decoded_at` (owned by meter) tracks when a link was decoded. Scheduled task in orinuno-source-kodik refreshes expired links via the `SourceCatalogEvent.VariantDecoded` outbox.
- **Bounded-context discipline (ADR 0016 + ADR 0021 E1)**:
  - No cross-context `@Autowired` of internal classes — only `*PublicApi` interfaces cross boundaries.
  - No cross-context `FOREIGN KEY` constraints in the database — cross-context references are soft.
  - orinuno-app may NOT import `com.kodik.*` except the gateway-level allow-list (`com.kodik.drift..`, `com.kodik.client.http..`, `com.kodik.client.embed..`). Enforced by `BoundedContextArchitectureTest.orinuno_app_does_not_reach_into_kodik_sdk_internals`.

## Development

```bash
# Docker compose (MySQL + every Boot service) — Dockerfile builds the multi-module reactor
cp .env.example .env   # set KODIK_TOKEN
docker compose up -d

# Manual run (each Boot module has its own spring-boot:run)
mvn -pl orinuno-app -am spring-boot:run
mvn -pl orinuno-source-kodik -am spring-boot:run
mvn -pl orinuno-source-jutsu -am spring-boot:run
mvn -pl meter -am spring-boot:run

# Tests (whole reactor)
mvn test

# Tests (single module)
mvn -pl orinuno-source-contract test
mvn -pl kodik-sdk test
mvn -pl jutsu-sdk test
mvn -pl sibnet-sdk test
mvn -pl aniboom-sdk test
mvn -pl orinuno-app test
mvn -pl orinuno-source-kodik test
mvn -pl orinuno-source-jutsu test
mvn -pl meter test

# Spotless on the local laptop occasionally trips on the JDK 25 toolchain
# (parent pom pins spotless-maven-plugin 2.46.1, which breaks on JDK 25 +
# Lombok). Pass -Dspotless.check.skip=true for local runs; CI runs on JDK 21.
mvn -Dspotless.check.skip=true -pl orinuno-app -am test
```

## Git

- Never change git config.
- Never commit or push without explicit user permission.
- Never commit files containing real API tokens or secrets.

## Architecture Diagrams

See `ARCHITECTURE.md` for Mermaid diagrams (system context, sequence diagrams, ER diagram, decoder pipeline, integration guide).

## Backlog & Competitive Context

**Read `BACKLOG.md` before starting feature work.** It contains:
- prioritized tasks (tech debt + ideas from competitive analysis)
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

See `TECH_DEBT.md`, `BACKLOG.md`, and the open boxes on
`docs/adr/0021-phase-2-5-write-path-cleanup.md` for active follow-ups
(D5 `KodikDumpBootstrapService` relocation, mybatis-spring-boot-starter
dep-trim from orinuno-app's production classpath, multi-instance
Caffeine cache lag bounds, schema drift Level 2 persistent storage).
