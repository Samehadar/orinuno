# ADR 0021 — Phase 2 + Phase 5 incomplete: L1/L2 dual-write still in orinuno-app

Status: **Accepted** — records the discrepancy between the ADR 0018/0019/0020 trackers and the actual code state on `feat/per-source-services-split` as of 2026-05-12. Defines the close-out plan as a sequence of small commits ("Z2 blocks") to be picked up in follow-up sessions.

Date: 2026-05-12

Refines: ADR 0018, ADR 0019, ADR 0020. Does NOT supersede them — the architectural target stays the same. This ADR adds an honest "current state" snapshot and an itemised roadmap to reach the target.

## Context

The ADR 0018 / 0019 / 0020 trackers report Phase 0 → 5.11 ✅ done. A boundary audit on 2026-05-12 found that the trackers describe the **application-layer move** (catalog packages → meter, read-only repos in orinuno-app, pollers, reverse-proxy filters), but the **storage-layer cleanup is partial**:

| Layer | Tables | Plan (ADR 0018) says owner | Code says owner | Aligned? |
|-------|--------|----------------------------|-----------------|----------|
| L1 Kodik | `kodik_content`, `kodik_episode_variant`, `kodik_calendar_*`, `kodik_decoder_path_cache`, `kodik_content_enrichment`, `kodik_proxy` | `orinuno-source-kodik` | `orinuno-app` **and** `orinuno-source-kodik` create the same DDL in their respective schemas (`orinuno` + `orinuno_source_kodik`). Dual-write. | ✗ |
| L1 jut.su | `jutsu_title`, `jutsu_episode`, `jutsu_sync_state`, `jutsu_film` | `orinuno-source-jutsu` | `orinuno-app` **and** `orinuno-source-jutsu` create the same DDL. Dual-write. | ✗ |
| L2 episode | `episode_source`, `episode_video` | `meter` (per ADR 0018 §5.2) | `orinuno-app` (canonical) — `KodikEpisodeDualWriteService` writes here from `ParserService`. meter does not own these. | ✗ |
| L3 catalog | `catalog_content`, `catalog_content_external_id`, `catalog_episode`, `catalog_episode_source_link` | `meter` (per ADR 0020 §1) | `meter` (canonical, `orinuno_catalog` schema). `orinuno-app` had dead duplicate changelogs in the `orinuno` schema until commit `<this PR>` removed them. | ✓ (after A2-half) |
| Parse log | `orinuno_parse_request` | `orinuno-source-kodik` (per ADR 0018 §2.5) | `orinuno-app` **and** `orinuno-source-kodik` create the table in their respective schemas. Application code (controllers, services, decoder stack) still lives in `orinuno-app`. | ✗ |

ADR 0018 §2.2 said *"`orinuno-app` больше НЕ создаёт `kodik_content`, …"*; the changelog file `orinuno-app/src/main/resources/com/orinuno/db/changelog/scripts/20260410120000_create_kodik_content.sql` is still present and still active in the master `liquibase-changelog.yaml`. Same shape for the other L1 + L2 lines above.

**Root cause:** Phase 2 + Phase 5 were both started; Phase 5 finished only the L3 layer, and the source-kodik / source-jutsu schemas were added in *parallel* to orinuno-app's existing tables rather than replacing them. The application code that consumed the orinuno-schema copies (KodikVideoDecoderService, KodikEpisodeDualWriteService, ContentController, StreamController, VideoDownloadService, HlsManifestService, ExportDataService, ParserService, JutsuCatalogSyncScheduler, JutsuLiveFallbackService, …) was never migrated, so the orinuno-schema copies stayed load-bearing for monolith deploys and for code-paths the per-source services don't yet expose.

The trackers in ADR 0018 §"Tracker", ADR 0019 §"Tracker", and ADR 0020 §"Tracker" don't capture this gap. Reading them in isolation a contributor would believe orinuno-app is already a pure gateway. It is not.

## Decision

1. Accept the gap is real and durable until the work below lands. Do not retroactively un-tick the existing tracker rows — they correctly record *application-layer* milestones. Instead, this ADR adds the missing *storage-layer* tracker as a parallel checklist.

2. Treat the close-out as a sequence of small, independently shippable commits ("Z2 blocks"), not one big-bang refactor. Each block keeps the docker-compose stack green and the orinuno-app test suite passing. Failure mode of a half-finished block is "monolith profile breaks"; that is acceptable transiently because monolith profile already requires manual setup.

3. Defer Phase 2.5 (parse-request slice → source-kodik) until at least Block A (L1 + L2 storage clean-up) lands. The parse slice transitively depends on `ContentDto` / `ContentMapper` / `EntityFactory` / `KodikContent` / `EpisodeVariantRepository` — all of which are entangled with the L1/L2 write path. Moving the parse slice before the storage clean-up creates more dead code in orinuno-app, not less.

4. The block order below is the close-out plan. Each line is one PR.

## Roadmap — Z2 close-out blocks

### Block A — storage authority cleanup

- **A2-half (done in this PR)** — remove dead L3 + `remote_source_watermark` Liquibase from orinuno-app. Zero behavior change (orinuno-app's primary DS already didn't read those tables; canonical copies live in `orinuno_catalog`).
- **A3 (dumps)** — `KodikDumpService` + `DumpScheduler` + `KodikDumpBootstrapService` + `orinuno_dump_state` table — decide ownership. Either (a) keep in orinuno-app as platform infrastructure (the calendar/dump polling is source-agnostic in principle), or (b) move into `meter` as the catalog ingestion bootstrap. Recommendation: (a) for now, revisit only if jut.su or future sources start needing equivalent dump bootstrap.
- **A6** — drop L1 `kodik_*` changelogs from orinuno-app's `liquibase-changelog.yaml`. Predicate: every consumer of `KodikContent` / `KodikEpisodeVariant` in orinuno-app must be either (1) deleted, (2) moved to source-kodik, or (3) refactored to read via HTTP to source-kodik or via meter's L3 read-path. Blocks A8.
- **A7** — drop L1 `jutsu_*` changelogs from orinuno-app's `liquibase-changelog.yaml`. Same predicate, applied to `JutsuTitle` / `JutsuEpisode` consumers.

### Block B — application-layer dual-write removal

- **B2** — extend meter's `KodikRemoteEventPoller` / `CatalogSinkEventEmitter` to populate L2 (`episode_source`) in addition to L3 (`catalog_*`). ✅ landed in commit `d963a1b`. **`episode_video` is intentionally NOT written here** — the event payload only carries `SourceEpisodeVariant.mediaUrl` which is the pre-decode iframe / episode-page URL, never the decoded CDN URL. Decoded URLs reach meter via the separate path below.
- **B2-decoded** *(new prerequisite for B1, discovered after B2)* — the post-decode URL needs its own channel before `KodikEpisodeDualWriteService` can be safely removed. Two viable shapes:
  1. **Event extension.** Add a sealed variant (e.g. `SourceCatalogEvent.VariantDecoded`) or an optional `decodedMediaUrl` + `decodedQuality` pair on `SourceEpisodeVariant`. `KodikSourceEventProjection` joins `kodik_episode_variant` ⋈ `episode_video` and emits the decoded URL when present. `CatalogSinkEventEmitter` writes `episode_video.video_url` when the decoded fields are set.
  2. **Direct meter endpoint.** New `POST /api/v1/catalog/internal/episode-videos` on meter that takes `(sourceType, sourceId, season, episode, translatorId, quality, videoUrl, ttlSeconds)` and upserts `episode_video`. orinuno-app calls this from `ParserService` after a successful decode. Simpler than touching the contract, but adds an explicit orinuno→meter HTTP coupling that ADR 0020 §2 deliberately avoided for the read path.

  Preferred shape: option 1 (event extension) — keeps the contract as the single coordination surface and avoids the orinuno→meter HTTP hop. The contract version bump is paid once; subsequent sources benefit from the same channel.

- **B1** — once B2-decoded lands, `KodikEpisodeDualWriteService` is finally redundant. Delete it from orinuno-app, drop the `dualWriteService` field + call in `ParserService`, remove `EpisodeSourceRepository` / `EpisodeVideoRepository` / `EpisodeSource` / `EpisodeVideo` from orinuno-app's primary write surface. **Blocking observation captured on 2026-05-12:** the original ADR 0021 §B1 text assumed events already carried decoded URLs and that `KodikEpisodeDualWriteService` was a pure "mirror"; it is in fact the sole writer of `episode_video.video_url` after Phase 0.4 split. Removing it without B2-decoded breaks `StreamController` + `VideoDownloadService` for every new decode (each request would re-run the decoder, hitting Kodik rate limits + decode latency). Rolled back from the 2026-05-12 session; resume only after B2-decoded is green.
- **B3** — move L2 (`episode_source` + `episode_video`) Liquibase from `orinuno-app/src/main/resources/com/orinuno/db/changelog/scripts/` to `meter/src/main/resources/db/catalog-changelog/scripts/`. Update meter's master changelog. After B3, `orinuno` schema no longer hosts L2; the canonical home is `orinuno_catalog`. Same backfill caveat as L1 — pre-prod, no data loss risk.
  - **B3-a (split-out of B3)** ✅ landed in commits `600815b` (Liquibase) + `d4b3474` (entities + repos). meter's catalog DB now owns the L2 schema in parallel to orinuno-app's legacy copy.

### Block C — read-side Kodik controllers (orinuno-app → source-kodik or delete)

- **C1** — `ContentController` + `ContentService` (orinuno-app). Consumers: external `/api/v1/content/*`, `MultiSourceController`, `ExportDataService`, `SourceEventController`, `ParserService`. Three options per route: (a) move to source-kodik with reverse-proxy filter, (b) re-implement on top of meter's L3 catalog read-path, (c) delete if no external consumer. Inventory required first.
- **C2** — `StreamController` + `HlsManifestService`. Kodik CDN-specific. Move to source-kodik with reverse-proxy filter mirroring the `KodikUpstreamProxyFilter` pattern.
- **C3** — `DownloadController` + `VideoDownloadService`. Same shape — move to source-kodik. CDN proxy + local-file storage stays a source-kodik concern.
- **C4** — `ExportDataService` + `SourceEventMapper`. The `SourceEventMapper` is a Kodik L1 → `SourceCatalogEvent` translator; orinuno-source-kodik already has its own `KodikSourceEventProjection` + `KodikSourceEventMapper`. Probably deletable from orinuno-app after C1.
- **C5** — drop `EnrichmentService` / `KodikContentEnrichmentRepository` from orinuno-app, since enrichment is a Kodik L1-shape concern. Move to source-kodik or delete.

### Block D — parse-request slice (the original Phase 2.5)

- **D1** — once Block A + B + C land, `ParserService` + `KodikVideoDecoderService` + `RequestWorker` + decoder helpers have no orinuno-app domain dependencies left except the SDK (`com.kodik.*`) + contract (`com.orinuno.contract.source.*`). Move them to source-kodik. `ParseRequestController`, `ParseRequestService`, `ParseRequestDto`, `RequestHashService`, `ParseRequestMetrics`, `ProgressReporter`, `ThrottledProgressReporter`, `ParseRequestQueueService` go with them.
- **D2** — `ParseUpstreamProxyFilter` in orinuno-app, gated on `orinuno.source-kodik.base-url`. Symmetric with the existing `KodikUpstreamProxyFilter` / `JutsuUpstreamProxyFilter`.
- **D3** — delete the orinuno-app originals + the orinuno-schema `orinuno_parse_request` changelog. The table canonically lives in `orinuno_source_kodik` from then on.

### Block E — guards + docs

- **E1** — ArchUnit rule: orinuno-app classes must not import `com.kodik.client.*`, `com.kodik.decoder.*`, or any class under `com.orinuno.source.*`. Compile-time guard against regression.
- **E2** — `OrinunoProperties.KodikProperties` subtree → `KodikSourceProperties` in `orinuno-source-kodik` (or `kodik-sdk-spring-boot-starter`). orinuno-app loses Kodik-specific config knobs entirely.
- **E3** — update AGENTS.md "bounded contexts" diagram + README quick-start to match the final shape (orinuno = gateway only, no Kodik logic).

## Session log — 2026-05-12

- `7453e68` fix(docker-compose) — IPv6 demo port pin + jutsu env names (unrelated, lands first).
- `2543816` refactor(orinuno-app) — drop legacy Kodik+Jutsu controllers (Phase 4 cleanup, -2968 LOC).
- `7964b53` refactor(orinuno-app) — drop parse-slice deps from HealthController + delete dead ShikimoriDiscoveryService.
- `a768bc7` chore(orinuno-app) — drop dead L3 catalog_* + remote_source_watermark Liquibase scripts (**A2-half**).
- `add982e` docs(adr) — ADR 0021 + tracker honesty patches on 0018/0019/0020.
- `e69ff8d` chore(demo) — add pnpm-lock.yaml for reproducible Vite build.
- `8b4f10` chore(e2e-poster) — commit WireMock fixtures referenced by docker-compose.
- `600815b` feat(meter) — L2 `episode_source` + `episode_video` Liquibase in meter (**B3-a-1**).
- `d4b3474` feat(meter) — `EpisodeSource` + `EpisodeVideo` entities + MyBatis repos in meter (**B3-a-2**).
- `d963a1b` feat(meter) — `CatalogSinkEventEmitter` writes L2 `episode_source` from events (**B2**).
- `<this PR>` docs(adr) — record B2-decoded prerequisite + tracker refresh.

## Risks of leaving the gap open

- **Monolith profile divergence.** `mvn -P monolith` still relies on orinuno-app owning the L1/L2 path. As source-kodik / source-jutsu / meter add features, the monolith bundle has to either (a) follow with duplicated bean wiring, or (b) drop monolith support. Without explicit choice, the monolith profile rots.
- **Tracker drift.** Future ADRs cross-referencing 0018/0019/0020 trackers may make over-optimistic assumptions about what's already cleaned up. This ADR is the canonical "actually look here" pointer.
- **Onboarding confusion.** A new contributor reading the README + AGENTS.md + ADR 0020 will conclude orinuno-app is a thin gateway and reach for the wrong abstractions when adding a new controller. The longer the gap stays, the worse this gets.
- **Latent boundary violations.** `KodikEpisodeDualWriteService` writes to L2 from orinuno-app — Phase 5.9 DB grants don't currently catch this because docker-compose runs orinuno-app as root. The day someone wires production grants honestly, this service breaks unannounced.

## Tracker

| Item | Status |
|------|--------|
| ADR 0021 + index update | ✅ commit `add982e` |
| A2-half — drop dead L3 + watermark changelogs from orinuno-app | ✅ commit `a768bc7` |
| A3 — decide dumps ownership | ⏳ open |
| A6 — drop L1 kodik_* changelogs from orinuno-app | ⏳ blocked on Block B + C |
| A7 — drop L1 jutsu_* changelogs from orinuno-app | ⏳ blocked on jutsu sync scheduler move |
| B3-a-1 — L2 Liquibase in meter | ✅ commit `600815b` |
| B3-a-2 — L2 entities + repos in meter | ✅ commit `d4b3474` |
| B2 — `CatalogSinkEventEmitter` writes `episode_source` from events | ✅ commit `d963a1b` |
| B2-decoded — post-decode URL channel (event variant or meter API) | ⏳ open — newly identified prereq for B1 |
| B1 — delete `KodikEpisodeDualWriteService`, route through events | ⏳ blocked on B2-decoded |
| B3 — drop L2 Liquibase from orinuno-app | ⏳ blocked on B1 |
| C1 — `ContentController` + `ContentService` triage | ⏳ open |
| C2 — `StreamController` + `HlsManifestService` to source-kodik | ⏳ open |
| C3 — `DownloadController` + `VideoDownloadService` to source-kodik | ⏳ open |
| C4 — `ExportDataService` + `SourceEventMapper` removal | ⏳ open |
| C5 — enrichment slice removal | ⏳ open |
| D1 — parse slice to source-kodik (ADR 0018 Phase 2.5) | ⏳ blocked on Block A + B + C |
| D2 — `ParseUpstreamProxyFilter` | ⏳ blocked on D1 |
| D3 — delete orinuno-app parse originals + orinuno-schema table | ⏳ blocked on D1 |
| E1 — ArchUnit guard against Kodik imports in orinuno-app | ⏳ blocked on Block C |
| E2 — `OrinunoProperties.KodikProperties` → `KodikSourceProperties` | ⏳ blocked on Block C |
| E3 — README + AGENTS.md sync to final shape | ⏳ blocked on Block C |

## Cross-references

- ADR 0018 §2.2 / §2.5 / §5.6 — the original targets this ADR's gap measures against.
- ADR 0019 §"Tracker" — Phase 4 application-layer move only; jut.su L1 storage is still dual-written. A7 covers the jut.su clean-up.
- ADR 0020 §1 — meter as single writer; §3 — DB user separation. B1+B2+B3 make this true at the L2 layer where today it isn't.
- Session memory: `~/.claude/projects/-Users-samehadar-projects-kodik/memory/project_orinuno_phase_2_5_state.md`.
