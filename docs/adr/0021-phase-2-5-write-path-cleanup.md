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
- **A3 (dumps)** — `KodikDumpService` + `DumpScheduler` + `KodikDumpBootstrapService` + `orinuno_dump_state` table. **Decision (2026-05-12): Kodik-specific, eventually moves to `orinuno-source-kodik`.** Evidence: `KodikDumpService` imports `com.kodik.client.http.RotatingUserAgentProvider` from kodik-sdk and polls `https://dumps.kodikres.com/{calendar,serials,films}.json`; `KodikDumpBootstrapService` injects orinuno-app's `ContentService` to write `kodik_content` rows. None of the surface is source-agnostic — the "platform" framing in the earlier audit was misleading. **Blocked on C1** (the ContentService stack must move before the bootstrap service can follow). Stays in orinuno-app for now without a tracker `⏳ open` shame — its blocker is C1, not a missing decision.
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

Block C is the application-layer counterpart of Block A. Each sub-block is one PR; chained so the docker-compose stack stays green between them. Decomposition derived from a code audit on 2026-05-12 (see "Block C audit notes" further down).

#### C0 — kill the dead duplicate first (zero-risk warm-up)

- **C0.1** — delete `orinuno-app/.../controller/SourceEventController.java` and the corresponding test. The route `GET /api/v1/source-events/ready` is already exposed by `orinuno-source-kodik`'s own `SourceEventController` (and that's where meter's `KodikRemoteEventPoller` actually points). The orinuno-app copy is a transitional leftover from before the Phase 5.5 split — no remaining consumer; `KodikUpstreamProxyFilter` already proxies `/api/v1/source-events/` to source-kodik. Confirms by greps + `meter/application.yml` (`orinuno.source-kodik.base-url`) + `KodikRemoteEventPoller.fetch(...)`. Dead code, one commit.

#### C1 — move ContentController/ContentService READ surface to source-kodik

Demo UI is the load-bearing consumer (`demo/src/api/client.ts` lines 66 / 71 / 75 / 79 hit all four `/api/v1/content/*` routes). Routes are pure Kodik L1 (`ContentDto` denormalises `KodikContent` + `kodik_episode_variant`), so they belong with source-kodik. Reverse-proxy mirrors `KodikUpstreamProxyFilter`'s `/api/v1/kodik/` route — same pattern, one more prefix.

- **C1.1** — port `ContentController` + the *read* half of `ContentService` (`findById`, `findByKinopoiskId`, `findAll`, `findVariantsByContentId`) + `ContentMapper.toDto(KodikContent | KodikEpisodeVariant)` + `ContentDto`/`EpisodeVariantDto` into `orinuno-source-kodik`. The class names + JSON shape stay identical so demo UI doesn't need a touch. source-kodik already has `ContentRepository` + `EpisodeVariantRepository` reads against its own `orinuno_source_kodik` schema (confirmed by `KodikSourceEventProjection`); no new schema work.

  In-process call from `MultiSourceController:94` (`contentService.findByKinopoiskId`) — drop it. `MultiSourceController` should look up the canonical `(sourceType, sourceId) → content_id` mapping via `catalog_content_external_id` (meter's L3) instead of bouncing through Kodik L1. Spelled out separately in **C1.3** below.

- **C1.2** — add `/api/v1/content/` to `KodikUpstreamProxyFilter.PROXY_PREFIXES`, gated on `orinuno.source-kodik.base-url`. Drop the orinuno-app `ContentController` + the read half of `ContentService` + `findAll` repo methods + the four `/api/v1/content/*` `MockMvc` tests. The two `ContentService.findOrCreate*` writers stay (still called by `ParserService` + `KodikDumpBootstrapService`; they go in Block D when the parse slice + dumps slice move).

- **C1.3** — refactor `MultiSourceController:82-106` to drop its `ContentService` dependency. Two viable shapes:
  1. New `catalog_content_external_id` query: `findContentIdBySourceTypeAndExternalId("kodik", "kinopoisk", kinopoiskId)` against the meter read-only DS. Pure read, no migration risk.
  2. WebClient call to `/api/v1/content/by-kinopoisk/{kinopoiskId}` (now in source-kodik). Adds an in-process hop; only acceptable because the route is already in the proxy filter so the latency story is cache-able by an upstream.

  Prefer (1) — keeps `MultiSourceController` source-agnostic (the whole reason it lives in orinuno-app). Add the read query to the existing meter-readonly `CatalogContentReadRepository`. Block A2-half already laid the read-only DS plumbing; only the SELECT is new.

#### C2 — move StreamController + HlsController + HlsManifestService to source-kodik

`/api/v1/stream/{variantId}` and `/api/v1/hls/{variantId}/{url,manifest}` are 100% Kodik-CDN-specific (they consume `kodik_episode_variant.mp4_link` + the kodik proxy bucket). No external surface other than the demo UI's player. Direct port.

- **C2.1** — port `StreamController` + `HlsController` + `HlsManifestService` + `KodikCdnHostMetrics` (it's already 100% Kodik) + the relevant `VideoDownloadService` helpers needed by streaming (lookups against `KodikEpisodeVariant`). Repos already exist on source-kodik for `KodikEpisodeVariant`.
- **C2.2** — add `/api/v1/stream/`, `/api/v1/hls/` to `KodikUpstreamProxyFilter.PROXY_PREFIXES`. Drop the originals in orinuno-app.

#### C3 — move DownloadController + VideoDownloadService

Local-file storage + ffmpeg remux. Single-source (kodik). Mirror of C2.

- **C3.1** — port `DownloadController` + `VideoDownloadService` + supporting `HlsProperties` config consumer + storage path config. The base path config moves into `KodikSourceProperties` (Block **E2**, do it first or co-commit).
- **C3.2** — add `/api/v1/download/` to `KodikUpstreamProxyFilter`. Drop orinuno-app originals.

#### C4 — kill ExportDataService duplication, port ExportController to source-kodik

`ExportController` + `ExportDataService` produce `ContentExportDto` (denormalised `KodikContent` + decoded variants). The whole pipeline is L1 Kodik; source-kodik already has `KodikSourceEventProjection` doing the same join differently. Two flavours:

- **C4.1** — port `ExportController` + the L1-Kodik bits of `ExportDataService` (`getExportData`, `getReadyForExport`) + `ContentMapper.toExportDto` + `ContentExportDto`. Add `/api/v1/export/` to `KodikUpstreamProxyFilter`. Drop orinuno-app originals.
- **C4.2** — delete the source-event-projection sibling `findReadyForExportAsEvents` from the orinuno-app `ExportDataService` (the half **C0.1**'s controller was using); source-kodik's `KodikSourceEventProjection` is the canonical version. Delete `com.orinuno.mapper.SourceEventMapper` as part of the same commit — orinuno-source-kodik has its own `KodikSourceEventMapper`.

#### C5 — drop the enrichment slice from orinuno-app

`EnrichmentService` + `KodikContentEnrichmentRepository` + the `kodik_content_enrichment` reads on `KodikContent`. Pure Kodik L1 concern, source-kodik already has the table. Confirm callers; if exactly mirror of source-kodik, delete in orinuno-app rather than relocate.

- **C5.1** — `find orinuno-app/src/main/java -name 'EnrichmentService*'` + `grep -r EnrichmentService orinuno-app/src` to scope. If non-self callers exist (e.g. ContentMapper hydrating `ContentDto.enrichment`), they migrate with the affected route to source-kodik. Otherwise: delete.

#### Block C audit notes (2026-05-12)

- `ContentController` routes are all read-only and demo-UI-load-bearing; (b) "reimplement on meter L3" rejected because the chrome it returns (translator titles, kodik-specific episode quality, kodik_link iframe URL) doesn't fit the L3 schema. (a) reverse-proxy keeps the wire shape identical.
- `ContentService.findOrCreateContent` + `saveVariants` writers stay until Block D — they're hot-path for `ParserService` + `KodikDumpBootstrapService` which are themselves blocked-on-Block-D.
- `MultiSourceController` is a cross-source orchestrator (`/api/v1/anime/*` returns `kodik`/`jutsu`/`sibnet`/`aniboom` candidates). It stays in orinuno-app permanently. Its repos (`EpisodeSourceRepository` + `EpisodeVideoRepository`) currently point at the orinuno-schema L2 tables, which lost their writer after B1 (commit `e7e7e0a`). **C1.3** flips them to the meter-readonly `orinuno_catalog` schema as a side-effect of dropping the `ContentService` dependency — that's the read-path fix the B3 row was waiting for.
- `SourceEventController` is dead duplicate after the Phase 5.5 cutover; **C0.1** retires it as a zero-risk warm-up commit.
- Demo UI's API client lives in `demo/src/api/client.ts`. After Block C lands, only `/api/v1/anime/*` (MultiSourceController) and `/api/v1/sources/*` (sources controllers) remain orinuno-app-served. Everything else proxies to source-kodik. AGENTS.md update in **E3** can quote these two lists verbatim.

### Block D — parse-request slice (the original Phase 2.5)

The 2026-05-12 audit revealed that a previous session already pre-staged most of the data layer in source-kodik. The remaining work is the application layer (services + controllers + decoder helpers) plus the cutover. Concrete decomposition follows.

#### What's already in source-kodik (D-prep, no further work)

- `model/ParseRequestPhase`, `model/ParseRequestStatus`, `model/OrinunoParseRequest`
- `model/KodikDecoderPathCacheEntry`
- `repository/ParseRequestRepository`, `repository/KodikDecoderPathCacheRepository`
- `repository/ContentRepository`, `repository/EpisodeVariantRepository`
- `db/changelog/scripts/20260426010000_create_orinuno_parse_request.sql` + `db/changelog/scripts/20260502020000_create_kodik_decoder_path_cache.sql`
- After commit `e8f6a26`: `model/dto/ParseRequestDto`, `model/dto/ParseRequestDtoView`, `service/requestlog/RequestHashService`, `service/requestlog/ParseRequestMetrics` (metric names rebased to `orinuno.source.kodik.*`)

#### D1a — port queue services (no decoder coupling)

- `service/requestlog/ParseRequestService` (queue API: enqueue / find / list / cancel). Drops the `OrinunoProperties.RequestsProperties` dep — inline the two knobs (`defaultPageLimit` / `maxPageLimit`) as `@Value("${orinuno.source-kodik.requests.default-page-limit:50}")` etc., or introduce a small `SourceKodikRequestsProperties` record.
- `service/requestlog/ParseRequestQueueService` (durable claim / heartbeat / terminal-transition primitive).
- `service/requestlog/ProgressReporter` (interface).
- `service/requestlog/ThrottledProgressReporter` (DB heartbeat throttle implementation).

No external callers in source-kodik yet → these are orphans until D1c lands ParseRequestController. That's fine for the migration sequence.

#### D1b — port decoder stack

- `service/KodikVideoDecoderService` (regex/JS decoder against Kodik iframe).
- `service/PlaywrightVideoFetcher` (Playwright network-sniff fallback).
- `service/decoder/KodikDecodeOrchestrator` (DECODE-8 router across REGEX/SNIFF).
- `service/decoder/PlaywrightSniffDecoder`.
- `service/decoder/DecoderPathCache` (in-memory + DB cache for decoded paths).
- `service/metrics/KodikCdnHostMetrics` (CDN host hit-rate counter — already Kodik-specific).

These pull in `kodik-sdk` (already a dep) + `OrinunoProperties.DecoderProperties` + `PlaywrightProperties`. Decoder + Playwright config knobs need to migrate too — either inlined or as a new `SourceKodikDecoderProperties`/`SourceKodikPlaywrightProperties` slice.

#### D1c — port the orchestrator + controllers

- `service/ParserService` (the full parse pipeline: search → decode → persist). After D1a + D1b it depends only on classes inside source-kodik + the SDK + the contract.
- `service/requestlog/RequestWorker` (scheduled queue consumer).
- `mapper/EntityFactory` (KodikSearchResponse → KodikContent / KodikEpisodeVariant entity builder).
- `controller/ParseController` (`POST /api/v1/parse/search`, `/decode/*`).
- `controller/ParseRequestController` (`POST /api/v1/parse/requests`, `GET /api/v1/parse/requests/{id}`).

The `MeterDecodedEventPublisher` (commit `297e931`, currently in orinuno-app) moves with ParserService too — it consumes the SDK's `DecodeAttemptResult` + the contract's `SourceCatalogEvent`, no orinuno-app domain types.

#### D1d — port matching tests

- `ParserServiceTest` + `ParserServiceErrorPathsTest`
- `DecoderMaintenanceSchedulerTest`
- `ParseRequestServiceTest` + `ParseRequestQueueServiceTest`
- `RequestWorkerTest`
- `MeterDecodedEventPublisherTest`
- `KodikDecodeOrchestratorTest`
- etc.

Test ports happen alongside their main-class siblings; this is bookkeeping for the tracker.

#### D2 — reverse-proxy filter prefix

After D1c, `/api/v1/parse/` is served by source-kodik. Add `"/api/v1/parse/"` to `KodikUpstreamProxyFilter.PROXY_PREFIXES` so demo UI + external callers keep hitting orinuno-app and get transparently forwarded. Same pattern as C1.2 / C4.2 cutovers.

#### D3 — delete originals + orinuno-schema parse-request table

- Delete `orinuno-app/.../service/ParserService.java` + `KodikVideoDecoderService.java` + the entire `service/decoder/` + `service/requestlog/` packages + matching tests.
- Delete `orinuno-app/.../controller/ParseController.java` + `ParseRequestController.java`.
- Delete `orinuno-app/.../mapper/EntityFactory.java`.
- Delete `orinuno-app/.../model/OrinunoParseRequest.java` + `ParseRequestPhase.java` + `ParseRequestStatus.java` + `model/dto/ParseRequestDto.java` + `ParseRequestDtoView.java`.
- Delete `orinuno-app/.../service/MeterDecodedEventPublisher.java` (moves with ParserService in D1c).
- Delete `repository/ParseRequestRepository.java` + matching XML mapper.
- Delete the orinuno-schema `20260426010000_create_orinuno_parse_request.sql` changeset + drop from master `liquibase-changelog.yaml`. Same `EpisodeVariantMapper` JOIN caveat as B3-full: deferred until the remaining JOIN-against-orinuno-schema-L2 queries in `EpisodeVariantMapper.xml` migrate off the primary DS.
- Delete `ContentService.java` + `ContentMapper.java` (after D1c moves their last writers / users out).
- Delete `service/PlaywrightVideoFetcher.java` and its config.
- Drop the `OrinunoProperties.KodikProperties` subtree (Block E2 piggybacks here).

Sequence: D3 must wait for `EpisodeVariantMapper.xml` decoded-skip JOINs to be migrated off the orinuno-schema L2 tables (or for ParserService's decoded-skip query to move into the source-kodik decoder), so this is the final blocker on B3-full as well.

#### D5 — KodikDumpBootstrapService follow-up

A3 decided the dumps slice (`KodikDumpService`, `KodikDumpBootstrapService`, `DumpScheduler`, `orinuno_dump_state` table) stays Kodik-specific and moves with the parse pipeline. After D1c lands, the bootstrap service's last orinuno-app dep (`ContentService.findOrCreateContent` + `saveVariants`) goes away — port it alongside D3's cleanup or as a small follow-up. The matching `20260502010000_create_orinuno_dump_state.sql` changeset can move to source-kodik then too.

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
- `92c5493` docs(adr-0021) — record B2-decoded prerequisite + tracker refresh.
- `099bff8` docs(adr-0021) — A3 dumps decision (Kodik-specific, blocked on C1).
- `3a3ea31` test(meter) — B2 end-to-end pipeline IT pins the SourceCatalogEvent →
  catalog_content + episode_source contract against real MyBatis + Testcontainers
  MySQL; also fixes meter/pom.xml so `-DexcludedGroups=none` actually overrides
  the `e2e` filter from the CLI.
- `fb04e92` feat(contract+meter) — **B2-decoded option 1**: sealed-family entry
  `SourceCatalogEvent.VariantDecoded` + meter's `CatalogSinkEventEmitter` handles
  the new variant via `EpisodeVideoRepository.upsertDecoded`. New push endpoint
  `POST /api/v1/source-events/decoded` accepts a `List<SourceCatalogEvent>` body
  (sealed family routed via the same emitter).
- `297e931` feat(orinuno-app) — `MeterDecodedEventPublisher` (WebClient,
  fire-and-forget, gated on `orinuno.meter.base-url`) wired into
  `ParserService.persistDecode` alongside the legacy
  `KodikEpisodeDualWriteService`. B1 (delete KEDW) is now unblocked but
  deliberately not in this PR — KEDW stays running in parallel for one
  migration window so a missed publish doesn't lose data.
- `4d13912` test(meter) — `B2EpisodeSourcePipelineIT` extended with a
  VariantDecoded → `episode_video` test that uses the autowired controller
  bean directly (Spring context + Testcontainers MySQL stay shared with the
  poller-path test).
- `e7e7e0a` refactor(orinuno-app) — **B1** closes. `KodikEpisodeDualWriteService`
  + its test are gone (-298 LOC); `ParserService.persistDecode` no longer
  touches `episode_source` / `episode_video` directly. meter is now the sole
  writer of both L2 tables. `EpisodeSource` / `EpisodeVideo` models + repos
  stay in orinuno-app as **read-only** for `MultiSourceController` +
  `MultiSourceRanker` — those readers see the orinuno-schema L2 tables go
  stale until Block B3/C migrates them to read from `orinuno_catalog`.
- `1cded62` refactor(orinuno-app) — **C0.1**: dead `SourceEventController`
  in orinuno-app (dup of source-kodik's), its unit test, and its end-to-end
  poster IT all deleted. -491 LOC.
- `97606eb` feat(source-kodik) — **C1.1**: `ContentController` +
  read half of `ContentService` ported into orinuno-source-kodik with
  field-for-field identical `ContentDto`/`EpisodeVariantDto`/`PageRequest`/`PageResponse`
  so demo UI sees no diff. +521 LOC across 8 new files (4 DTOs + mapper +
  service + controller + controller test).
- `83daac3` refactor(orinuno-app) — **C1.2**: cutover. `/api/v1/content/`
  added to `KodikUpstreamProxyFilter.PROXY_PREFIXES`; orinuno-app
  `ContentController` + its `MockMvc` test deleted. ContentService + the
  four DTOs stay (still referenced by `MultiSourceController` →
  `ParseController` / `ExportController` / `KodikDumpBootstrapService`).
- `cbc6b98` refactor(orinuno-app) — **C1.3**: `MultiSourceController`
  switches from `ContentService.findByKinopoiskId` to direct
  `ContentRepository.findByKinopoiskId`. Frees orinuno-app from holding
  the `ContentService` read half just for this one lookup; the read
  itself still hits the orinuno-schema `kodik_content` (which has a live
  writer via `ParserService.findOrCreateContent`). The L2 flip (real
  B1-stale-read fix) split out as **C1.4** because every current deploy
  has `orinuno.catalog-read.url` unset.
- `00053e0` refactor(orinuno-app) — **C1.4**: `MultiSourceController`
  reads `episode_source` + `episode_video` from `orinuno_catalog` via
  two new JdbcTemplate-backed `CatalogEpisode{Source,Video}ReadRepository`
  classes. Controller + new repos all gated on the existing
  `catalogReadJdbcTemplate` bean (Phase 5.4); monolith deploys without
  the readonly DS configured boot with the controller bean absent →
  `/api/v1/anime/*` returns 404, which the monolith profile flags
  explicitly. `docker-compose.yml` gains
  `ORINUNO_CATALOG_READ_URL=jdbc:mysql://db/orinuno_catalog`; the
  monolith overlay sets it to empty.
- `fa7f050` refactor(orinuno-app) — **B3-partial**: the now-orphan
  primary-DS MyBatis path for L2
  (`com.orinuno.repository.EpisodeSourceRepository` +
  `EpisodeVideoRepository` + their XML mappers) deleted. -202 LOC. The
  Liquibase changesets stay until `EpisodeVariantMapper.xml`'s JOINs
  (used by ParserService's decoded-skip + TTL-expiry queries) migrate
  off the primary DS — recorded as B3-full in the tracker, blocked on
  Block D's parse-slice move.
- `c4127a7` feat(source-kodik) — **C4.1**: `ExportController` +
  `ContentExportService` + `ContentExportDto` + `toExportDto` mapper
  helpers (extractPosterUrl / deriveOngoing / etc.) ported to
  source-kodik. +314 LOC across 4 new files.
- `044deda` refactor(orinuno-app) — **C4.2**: cutover.
  `KodikUpstreamProxyFilter.PROXY_PREFIXES` gains `/api/v1/export/`;
  legacy `ExportController` + `ExportDataService` + `SourceEventMapper`
  + `ContentExportDto` + `ExportDataServiceTest` +
  `SourceEventMapperTest` + `KodikPosterShapeLiveIT` deleted;
  `ContentMapper` trimmed to the two read-side `toDto` overloads.
  -1285 LOC across 9 files.
- `5335517` refactor(orinuno-app) — **C5**: orphan META-1 enrichment
  slice gone. `service/enrichment/*` (5 clients + EnrichmentService) +
  `KodikContentEnrichmentRepository` + `KodikContentEnrichment` model +
  XML mapper + Liquibase changeset + 2 unit tests deleted. -888 LOC
  across 12 files. Audit confirmed nothing outside the enrichment
  package referenced the service; source-kodik keeps its own copy of
  the table for future META-1 work.
- `e8f6a26` feat(source-kodik) — **D-prep**: parse-request leaf classes
  (ParseRequestDto + ParseRequestDtoView + RequestHashService +
  ParseRequestMetrics) added to source-kodik. +257 LOC, no orinuno-app
  changes. Combined with the previous-session D-prep that landed the
  data layer (Phase/Status models, OrinunoParseRequest, repositories,
  Liquibase changeset), source-kodik now hosts ~half the Block D files
  needed before D1a / D1b / D1c can land. Refined Block D decomposition
  in this PR.
- `206195c` feat(source-kodik) — **D1a**: parse-request queue services
  (ParseRequestService + ParseRequestQueueService + ProgressReporter +
  ThrottledProgressReporter) ported into source-kodik. +436 LOC.
  ParseRequestService drops its OrinunoProperties.RequestsProperties dep
  in favour of two @Value-injected knobs scoped to
  `orinuno.source-kodik.requests.*` (defaults 50 / 200 preserve legacy
  values). `Application.java` gains a `@Bean Clock` /
  `@ConditionalOnMissingBean` so the new queue services wire up. No
  orinuno-app changes — dual copies coexist until D3 retires the
  originals.
- `648177a` feat(source-kodik) — **D1b-prep**: DecoderPathCache (DECODE-2
  per-netloc memo) + KodikCdnHostMetrics (rebased to
  `orinuno.source.kodik.cdn.host` namespace) ported into source-kodik.
  +214 LOC.
- `8ce7ae9` feat(source-kodik) — **D1b-1**: scaffolding for the heavy
  decoder ports. KodikDecoderProperties (@ConfigurationProperties under
  `orinuno.source-kodik.decoder.*`) + KodikWebClientConfiguration
  extended with kodikPlayerWebClient + kodikCdnWebClient beans +
  DecoderHealthTracker + GeoBlockDetector + Application
  @ConfigurationPropertiesScan. +191 LOC.
- `d3744ba` feat(source-kodik) — **D1b-2**: KodikVideoDecoderService
  (the 555-LOC regex/JS decoder) + KodikUpstreamErrorClassifier +
  ProxyRepository / ProxyMapper.xml / ProxyProviderService /
  ProxyWebClientService ported. ProxyProviderService rebases its single
  config knob to `orinuno.source-kodik.proxy.enabled` (default false).
  +862 LOC.
- `ae52972` feat(source-kodik) — **D1b-2.5**: KodikDecodeOrchestrator
  ported in sniff-less form (sniff branch deferred to D1b-3, since
  PlaywrightVideoFetcher + HLS helpers still pending). +79 LOC.
- `b170a11` feat(source-kodik) — **D1b-3**: Playwright stack ported.
  PlaywrightVideoFetcher (872 LOC, including HLS-via-Playwright fetch
  logic) + PlaywrightSniffDecoder + HLS helpers (HlsRetryPolicy +
  HlsMediaPlaylist + HlsManifestParser + HlsMasterPlaylistResolver) +
  KodikPlaywrightProperties + DownloadProgress (promoted to top-level
  class from the nested VideoDownloadService.DownloadProgress). pom
  gains com.microsoft.playwright:playwright:1.59.0. Orchestrator's
  sniff-fallback branch restored. +1458 LOC. Block D1b fully closed.
- `77d93dc` feat(source-kodik) — **D1c**: ParserService (418 LOC) +
  RequestWorker + ParseController + ParseRequestController +
  EntityFactory + ContentWriteService + MeterDecodedEventPublisher +
  KodikRequestsProperties ported. ParserService drops the legacy
  OrinunoProperties dep in favour of KodikDecoderProperties (with new
  `requestDelayMs` field absorbed from
  `orinuno.kodik.request-delay-ms`); RequestWorker similarly rebases
  onto KodikRequestsProperties (`orinuno.source-kodik.requests.*`).
  ContentWriteService is the source-kodik counterpart of orinuno-app's
  ContentService write half. MeterDecodedEventPublisher rebases its
  config prefix to `orinuno.source-kodik.meter.base-url`. +1103 LOC.
- `c3543c5` refactor(orinuno-app) — **D2**: KodikUpstreamProxyFilter
  gains `/api/v1/parse/`. The orinuno-app ParseController +
  ParseRequestController stay wired locally (filter dormant when
  source-kodik.base-url unset; monolith profile keeps serving from
  the in-process beans). D3 retires originals once C2/C3 free the
  decoder + Playwright + VideoDownloadService cross-dependencies.
- `06370b3` feat(source-kodik) — **C2.1**: stream/hls + download surface
  ported. StreamController (243 LOC) + HlsController (70 LOC) +
  HlsManifestService (127 LOC) + VideoDownloadService (514 LOC after
  the nested DownloadProgress.toState helper extracted into a static
  on VideoDownloadService — DownloadProgress moved to top-level in
  D1b-3 so PlaywrightVideoFetcher could share it) + KodikStorageProperties
  (`orinuno.source-kodik.storage.*`). +973 LOC. Pulls
  VideoDownloadService along because StreamController consumes it;
  C3.1 narrows to just DownloadController.
- `9e371a3` refactor(orinuno-app) — **C2.2**: KodikUpstreamProxyFilter
  gains `/api/v1/stream/` + `/api/v1/hls/`; orinuno-app's
  StreamController + HlsController + HlsManifestService deleted
  (-441 LOC). VideoDownloadService + KodikVideoDecoderService +
  PlaywrightVideoFetcher + the rest of the decoder stack stay in
  orinuno-app until C3 + D3 retire DownloadController + ParserService.
- `1179fda` test(source-kodik) — **D1d**: 14 test classes ported
  covering the parse pipeline + decoder stack + queue services +
  supporting beans (ParseController/RequestController, ParserService,
  KodikVideoDecoderService × 3 variants, PlaywrightVideoFetcher,
  DecoderPathCache, KodikDecodeOrchestrator, PlaywrightSniffDecoder,
  KodikCdnHostMetrics, ParseRequestMetrics, RequestWorker,
  MeterDecodedEventPublisher, ParseRequestDto). Test-side property
  classes rebased to the new flat KodikDecoder/Playwright/Requests
  surfaces; ParseRequestMetricsTest assertion names rebased to
  `orinuno.source.kodik.parse.*`. pom: adds reactor-test test-scope
  dep. 116 tests pass on source-kodik (was 17). +2054 LOC.
- `aca0475` refactor(orinuno-app) — **A7** closes. -4716 LOC across 41 files:
  drops `com.orinuno.jutsu.*` (model + repository + sync schedulers + live-fallback +
  read), `com.orinuno.model.dto.jutsu.*`, `JutsuFallbackConfiguration`, the 6 `jutsu_*`
  Liquibase changesets, and the matching test tree. `OrinunoProperties.JutsuProperties`
  keeps the SDK-related subtree (credentials, drift-probe) consumed by
  `JutsuSdkConfiguration` + `JutsuDriftScheduledProbe` + the multi-source controllers;
  `sync.*` and `fallback.*` are gone. ArchUnit `BoundedContextArchitectureTest` still
  green because its rules pin the jutsu-sdk surface, not the deleted L1 package.

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
| A3 — decide dumps ownership | ✅ decided 2026-05-12 (Kodik-specific, blocked on C1 to relocate) |
| A6 — drop L1 kodik_* changelogs from orinuno-app | ⏳ blocked on Block B + C |
| A7 — drop L1 jutsu_* changelogs from orinuno-app | ✅ commit `aca0475` (also dropped the jutsu sync schedulers + live-fallback + read services + DTOs — none had non-self callers after `2543816`) |
| B3-a-1 — L2 Liquibase in meter | ✅ commit `600815b` |
| B3-a-2 — L2 entities + repos in meter | ✅ commit `d4b3474` |
| B2 — `CatalogSinkEventEmitter` writes `episode_source` from events | ✅ commit `d963a1b` |
| B2 end-to-end IT — Spring context + Testcontainers MySQL, poll → emit → row | ✅ commit `3a3ea31` |
| B2-decoded — post-decode URL channel (event variant + meter push endpoint) | ✅ commits `fb04e92` (contract+meter), `297e931` (orinuno-app emit), `4d13912` (IT) |
| B1 — delete `KodikEpisodeDualWriteService`, route through events | ✅ commit `e7e7e0a` (models + repos stay read-only for `MultiSourceController` / `MultiSourceRanker`; orinuno-schema L2 tables stop receiving fresh writes — known stale-read trade-off until Block B3/C) |
| B3-partial — drop orphan MyBatis L2 surface (repos + mappers) from orinuno-app | ✅ commit `fa7f050` |
| B3-full — drop L2 Liquibase from orinuno-app (incl. backfill) | ⏳ blocked on migrating `EpisodeVariantMapper.xml`'s JOINs (`findByIdWithDecodedVideo` / `findExpiredLinks` / similar) off the orinuno-schema L2 tables — either via the meter-readonly JdbcTemplate or by moving the decoded-skip logic into the source-kodik ParserService in Block D |
| C0.1 — delete dead orinuno-app `SourceEventController` (dup of source-kodik) | ✅ commit `1cded62` |
| C1.1 — port `ContentController` + read half of `ContentService` → source-kodik | ✅ commit `97606eb` |
| C1.2 — proxy `/api/v1/content/` via `KodikUpstreamProxyFilter`; drop orinuno-app originals | ✅ commit `83daac3` |
| C1.3 — `MultiSourceController` drops `ContentService` dep, calls `ContentRepository` directly | ✅ commit `cbc6b98` |
| C1.4 — flip `MultiSourceController` L2 reads to meter-readonly `orinuno_catalog` | ✅ commit `00053e0` (also wired `ORINUNO_CATALOG_READ_URL` into docker-compose.yml + monolith overlay) |
| C2.1 — port `StreamController` + `HlsController` + `HlsManifestService` + `VideoDownloadService` + `KodikStorageProperties` → source-kodik | ✅ commit `06370b3` (also pulled VideoDownloadService in since StreamController consumes it; C3.1 narrows to DownloadController only) |
| C2.2 — proxy `/api/v1/stream/` + `/api/v1/hls/`; drop orinuno-app originals | ✅ commit `9e371a3` |
| C3.1 — port `DownloadController` → source-kodik | ⏳ open — VideoDownloadService + storage props already moved in C2.1; only DownloadController itself remains |
| C3.2 — proxy `/api/v1/download/`; drop orinuno-app originals | ⏳ blocked on C3.1 |
| C4.1 — port `ExportController` + L1-Kodik half of `ExportDataService` → source-kodik | ✅ commit `c4127a7` |
| C4.2 — proxy `/api/v1/export/`; delete `ExportController`/`ExportDataService`/`SourceEventMapper`/`ContentExportDto`/poster live-IT in orinuno-app | ✅ commit `044deda` |
| C5 — drop enrichment slice from orinuno-app | ✅ commit `5335517` (orphan; nothing in orinuno-app calls EnrichmentService outside its own package + tests; source-kodik holds its own copy of the table for future META-1 reactivation) |
| D-prep — leaf data classes (DTOs + RequestHashService + ParseRequestMetrics) in source-kodik | ✅ commit `e8f6a26` |
| D1a — port queue services (ParseRequestService + ParseRequestQueueService + ProgressReporter + ThrottledProgressReporter) | ✅ commit `206195c` (also lands `@Bean Clock` in source-kodik's Application + rebases page-limit knobs to `orinuno.source-kodik.requests.*` — partial E2 ride-along) |
| D1b — port decoder stack | ✅ commits `648177a` (DecoderPathCache + KodikCdnHostMetrics), `8ce7ae9` (KodikDecoderProperties + WebClients + DecoderHealthTracker + GeoBlockDetector), `d3744ba` (KodikVideoDecoderService + KodikUpstreamErrorClassifier + ProxyRepository/Service/WebClient stack), `ae52972` (KodikDecodeOrchestrator sniff-less), `b170a11` (PlaywrightVideoFetcher + PlaywrightSniffDecoder + HLS helpers + DownloadProgress + KodikPlaywrightProperties + orchestrator sniff branch restored) |
| D1c — port ParserService + RequestWorker + EntityFactory + ParseController + ParseRequestController + MeterDecodedEventPublisher + ContentWriteService + KodikRequestsProperties | ✅ commit `77d93dc` |
| D1d — port matching tests | ✅ commit `1179fda` (14 test classes, 116 tests pass on source-kodik) |
| D2 — `/api/v1/parse/` → `KodikUpstreamProxyFilter` | ✅ commit `c3543c5` |
| D3 — delete orinuno-app parse originals + orinuno-schema `orinuno_parse_request` changeset; closes B3-full at the same time | ⏳ blocked on Block C2 + C3 (StreamController + DownloadController still consume KodikVideoDecoderService / PlaywrightVideoFetcher / VideoDownloadService in orinuno-app) |
| D5 — relocate `KodikDumpBootstrapService` + `orinuno_dump_state` changeset (A3 decision) | ⏳ blocked on D3 (needs `ContentService.findOrCreateContent` callers gone first) |
| E1 — ArchUnit guard against Kodik imports in orinuno-app | ⏳ blocked on Block C |
| E2 — `OrinunoProperties.KodikProperties` → `KodikSourceProperties` | ⏳ blocked on Block C |
| E3 — README + AGENTS.md sync to final shape | ⏳ blocked on Block C |

## Cross-references

- ADR 0018 §2.2 / §2.5 / §5.6 — the original targets this ADR's gap measures against.
- ADR 0019 §"Tracker" — Phase 4 application-layer move only; jut.su L1 storage is still dual-written. A7 covers the jut.su clean-up.
- ADR 0020 §1 — meter as single writer; §3 — DB user separation. B1+B2+B3 make this true at the L2 layer where today it isn't.
- Session memory: `~/.claude/projects/-Users-samehadar-projects-kodik/memory/project_orinuno_phase_2_5_state.md`.
