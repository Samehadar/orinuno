# Technical Debt

## ARCH-0018: JIT decode — stop persisting decoded mp4 URLs entirely

**Priority:** Medium (architectural, follow-up to ADR 0018 Phase 0.4c)
**Discovered:** 2026-05-11
**Reference:** [`docs/adr/0018-per-source-service-split-kodik.md`](docs/adr/0018-per-source-service-split-kodik.md)

**Context:** Kodik decoded URLs are TTL-bound (≤24h, often ≤1h depending on CDN). The current architecture stores them in `episode_video.video_url` (single writer = `KodikEpisodeDualWriteService`). Most consumers read the stored URL hours after decode — by then the URL is stale and the consumer must trigger another decode anyway.

A cleaner contract: **decode JIT (just-in-time) on every consumer request**, never persist the decoded URL. Implications:

- `episode_video.video_url` becomes vestigial for Kodik (Sibnet/Aniboom may still benefit because of their longer or unbounded TTLs — keep the schema, change the Kodik write path).
- `KodikEpisodeDualWriteService` either stops writing `episode_video` for Kodik decodes, or the table becomes a Kodik-specific decode-cache with explicit TTL eviction.
- `/api/v1/export/ready` switches from returning a stored `mp4Link` to returning the `kodikLink` (iframe URL); consumers call `/api/v1/parse/decode/variant/{id}` or `/api/v1/stream/{id}` when they actually need the bytes.
- `external bridge` (external aggregator) adapts to the new `SourceCatalogEvent` shape — `mediaUrl` field semantics flip from "stored decoded URL" to "iframe URL that the consumer must decode".

**Why deferred:** this is a public contract change (`/export/ready` payload, `SourceCatalogEvent.mediaUrl` semantics) and breaks downstream consumer until external aggregator ships its bridge update. Needs its own ADR with coordinated rollout — likely Phase 5/6 of the per-source-services-split work, when the OSS meter service is the active consumer.

**Recorded mitigations until then:** in-memory Caffeine cache for repeated reads within a short window (≤5 min) — buys low-latency without persistence. See Phase 6 of ADR 0018 for the Kafka-driven follow-up.

## ARCH-0016: `kodik_episode_variant` is an L1+L2 hybrid (RESOLVED — Phase 0.4c)

**Status:** ✅ Resolved 2026-05-11 (ADR 0018 Phase 0.4 — backfill + read-path migration + column drop landed in commits 2af23c7, c05094c, and Phase 0.4c).
**Priority:** Was Low (informational).
**Discovered:** 2026-05-07 (ADR 0016 audit)
**Reference:** [`docs/adr/0016-architecture-trajectory.md`](docs/adr/0016-architecture-trajectory.md) §"Known tech debt"

**Resolution summary:** Phase 0.4 of ADR 0018 dropped `mp4_link` / `mp4_link_decoded_at` / `decode_method` from `kodik_episode_variant` and moved the read-path onto `episode_video` (single writer = `KodikEpisodeDualWriteService`). Backfill migration `20260511000000_backfill_episode_video_from_kodik_variant.sql` covers legacy rows; drop migration `20260511010000_drop_kodik_variant_l2_columns.sql` removes the columns. Variant table is now L1-only. The deeper question of "should we persist the decoded URL at all" is tracked above as ARCH-0018 (JIT decode).

**Historical context (kept for future readers):**

**Context:** Per ADR 0016, the project follows a three-layer data model:

- L1 — per-source raw cache (mirror of upstream's catalogue).
- L2 — provider-agnostic episode pointers (`episode_source` + `episode_video`, ADR 0005).
- L3 — universal canonical catalog (`catalog_*`, P1b).

`kodik_episode_variant` predates ADR 0005 and currently mixes both L1 and L2 semantics in one table:

- **L1 columns** (mirror of upstream): `kodik_link` — raw URL from Kodik upstream.
- **L2 columns** (decoded URL bookkeeping): `mp4_link`, `mp4_link_decoded_at`, `mp4_link_failed_count`, `decode_method`, `local_filepath`. These are the same problem space as `episode_video.video_url` + `episode_video.decoded_at` + `episode_video.failed_count` for non-Kodik sources.

A clean L1/L2 split would migrate the L2 columns out of `kodik_episode_variant` into `episode_video` rows with `source_type=KODIK`, leaving `kodik_episode_variant` as a pure L1 mirror.

**Why deferred:** while we run as a single process the hybrid causes no operational harm — the COALESCE-upsert rule from `AGENTS.md` keeps `mp4_link` consistent, the TTL refresher works against `mp4_link_decoded_at`, and the export DTO knows where to look. The cost only materializes at the **moment of split**: extracting Kodik into its own service would mean either:

1. The Kodik service owns both L1 and L2 storage (i.e. duplicates `episode_video`-equivalent fields locally) — defeats the purpose of L2 as a provider-agnostic layer.
2. The Kodik service does the migration as part of the split — risky, large changeset under deadline pressure.

**Resolution:** deferred to **P5** in the ADR 0016 roadmap (when a real split trigger fires). The migration is a small but non-trivial Liquibase changeset:

1. New rows in `episode_source` with `source_type=KODIK` for every existing `kodik_episode_variant` (one per `(content_id, season, episode, translation_id)`).
2. Move `mp4_link`/`mp4_link_decoded_at`/`mp4_link_failed_count`/`decode_method` into `episode_video` rows linked to the new `episode_source`.
3. Drop the L2 columns from `kodik_episode_variant`. Update `KodikEpisodeVariantRepository`, `ParserService.decodeForVariant`, `VideoDownloadService`, and the export mapper accordingly.

For now: do **not** add new L2-shaped columns to `kodik_episode_variant`. Any new decoded-URL state for Kodik should go straight into `episode_video`.

## PF-I7: Async Jobs for Long-Running Decode Operations (PARTIAL — Phase 2 landing)

**Priority:** Medium
**Status:** Phase 2 introduces a request-log (`orinuno_parse_request`) plus
`POST /api/v1/parse/requests` that returns 201/200 immediately, a single
`@Scheduled(2s)` `RequestWorker`, idempotent submit by canonical-JSON SHA-256,
phase tracking (`QUEUED → SEARCHING → DECODING → DONE/FAILED`), throttled
progress updates and `recoverStale` for crash recovery. The existing synchronous
`/api/v1/parse/search` is preserved for direct callers.

The follow-ups below are now tracked individually (TD-PR-1..TD-PR-3) instead of
a single umbrella item.

**Reference:** <another-upstream> parser in external aggregator uses RabbitMQ + State Machine.
We deliberately picked DB-polling instead because orinuno is a standalone
service and downstream consumer is the only consumer of the request log.

## TD-PR-1: Single-thread RequestWorker

**Priority:** Medium
**Context:** Phase 2 ships exactly one `@Scheduled(2s)` worker thread per
orinuno instance, claimed via `SELECT … FOR UPDATE SKIP LOCKED` and processed
synchronously with a blocking `Mono.block()`. This keeps Kodik request-rate
predictable, avoids reactor↔MyBatis thread bridging, and makes the failure
modes obvious. Trade-off: a single slow request (e.g. 220 episodes ×
N translations) blocks every other request behind it.

**Proposed solution when load grows:**
- bump worker pool to N threads, each claiming its own row;
- migrate the pipeline to a fully reactive `WebClient` boundary (no `block()`);
- consider per-request worker prioritisation (ongoing first, gap-fill second).

Defer to Phase 4/5 — current single-source workload is well within one worker.

## TD-PR-2: Dry-write debug for /list proxy

**Priority:** Low
**Context:** `KodikListProxyService` deliberately does not persist incoming
Kodik payloads — drift detection runs in-memory and the controller forwards
the minimal `KodikListItemView` view. This is fine for the steady state but
makes after-the-fact investigation of intermittent Kodik shape changes harder
(the team has to be live-tailing logs while drift fires).

**Proposed solution:** optional `?dryWrite=true` query parameter (gated by
`orinuno.requests.list-debug-enabled=false`) that records the raw response
into a new `kodik_list_debug_log` table for offline replay/diff. Default off.

## TD-PR-4: /list nextPage cursor — token leakage

**Priority:** Low
**Discovered:** 2026-04-26 (live integration: downstream consumer discovery loop ↔
orinuno /api/v1/kodik/list).

**Context:** `KodikListProxyService` rewrites the upstream Kodik
`next_page`/`prev_page` URL into a relative proxy URL of the form
`/api/v1/kodik/list?next_page=<URL_ENCODED_UPSTREAM>` so callers can pass it
back unchanged. The encoded upstream URL still contains
`token=<kodik-token>` because `KodikApiClient.postForMapAbsoluteUrl(...)` POSTs
the raw absolute URL to Kodik and there is no token-injection branch on that
path. Net effect: the Kodik token bleeds into downstream consumer's
`kodik_discovery_state.last_next_page` column.

**Why it's only Low:** downstream consumer and orinuno share the same trust boundary
in the current deployment (both internal services, both behind the same API
key). The leak is *intra-stack* and never reaches an external client.

**Proposed solution (when we either open the proxy externally or rotate
tokens often enough to care):**

1. **Cursor-token approach (preferred).** Maintain a small short-lived
   `kodik_list_cursor` map keyed by an opaque random token; the response
   surfaces `?cursor=<opaque>` instead of the upstream URL. On follow-up
   the controller resolves the opaque cursor → upstream URL → forwards.
2. **Strip + re-inject.** Strip the `token=` query parameter before
   encoding; teach `postForMapAbsoluteUrl` to reattach the current
   `tokenRegistry.currentToken(...)` if the URL has none. Slightly
   simpler but couples token plumbing across two code paths.

**Not blocking for Phase 2.** Tracked separately so it does not get lost.

## TD-PR-5: Single shared scheduler thread blocks RequestWorker — **DONE** (2026-04-26)

**Priority:** Medium → fully closed in Phase 2.5 hardening.
**Discovered:** 2026-04-26 (live integration: 20 PENDING parse requests
were never claimed by `RequestWorker.tick()`).

**Original problem:** Spring Boot's default `TaskScheduler` is a single
threaded `ThreadPoolTaskScheduler` (pool=1). orinuno had multiple
`@Scheduled` methods sharing that pool:

- `RequestWorker.tick()` — every 2s, blocking;
- `RequestWorker.recoverStale()` — every 60s, blocking;
- `ParserService.refreshExpiredLinks()` — every 1h, **synchronous**
  `.block()` over a `Flux` of up to 50 expired variants × 30s decoder
  timeout × 3 retries (so the first invocation could pin the single
  scheduler thread for hours, especially under geo-block).
- `ParserService.retryFailedDecodes()` — same shape, init-delay 30 min.
- `KodikTokenLifecycle.validate()` — every 6h.

Under live VPN-induced geo-block, `refreshExpiredLinks` fired first and
held the scheduler thread; `RequestWorker.tick()` never ran, so the
parse-request queue stayed PENDING forever even though
`POST /api/v1/parse/requests` kept accepting new rows.

### Phase 2.5 fixes (all landed)

1. **Bounded blocking** —
   `OrinunoProperties.DecoderProperties.MaintenanceProperties` adds
   `maxBatchPerTick` (default 10) and `tickTimeoutSeconds` (default 600).
   `ParserService.refreshExpiredLinks` / `retryFailedDecodes` now cap
   the repository query and call `.block(Duration.ofSeconds(...))` so a
   single hung batch returns control after at most the configured
   wall-clock budget. Covered by `ParserServiceTest`
   `refreshExpiredLinksHonoursMaxBatchPerTick`,
   `retryFailedDecodesHonoursMaxBatchPerTick`,
   `refreshExpiredLinksAbortsOnTickTimeout`.

2. **Per-job pools** — `OrinunoApplication` now exposes two
   `TaskScheduler` beans:
   - `taskScheduler` (prefix `orinuno-sched-`, pool=2) drives the
     responsive `@Scheduled` jobs (`RequestWorker.tick()`,
     `recoverStale()`, `KodikTokenLifecycle.scheduledRevalidation()`).
   - `decoderMaintenanceTaskScheduler` (prefix
     `orinuno-decoder-maint-`, pool=2) is reserved for the long-running
     decoder maintenance jobs.

   `DecoderMaintenanceScheduler` (new component) registers the
   `refreshExpiredLinks` / `retryFailedDecodes` ticks against the
   maintenance pool via `scheduleWithFixedDelay`. `@Scheduled` on those
   `ParserService` methods was removed. Verified by
   `DecoderMaintenanceSchedulerTest.runsOnDedicatedPool`.

3. **Observability (Phase 2.5)** —
   `com.orinuno.service.requestlog.ParseRequestMetrics` registers:
   - `orinuno.parse.requests` (gauge, tag `status`)
   - `orinuno.parse.request.worker.tick` (timer, p50/p95/p99)
   - `orinuno.parse.request.processing` (timer, tag `outcome`)
   - `orinuno.parse.requests.completed` (counter, tag `outcome`)

   Exposed at `/actuator/prometheus`. The repo also ships a local
   Prometheus + Grafana stack under `observability/` and the
   `observability` Compose profile, with a pre-provisioned dashboard
   `orinuno → orinuno — parse requests`. See
   `docs-site/src/content/docs/operations/monitoring.md`.

**How to verify locally:**

```sh
docker compose --profile observability up -d prometheus grafana
mvn spring-boot:run                      # or `docker compose up app`
open http://localhost:3001/d/orinuno-parse-requests
```

## TD-PR-3: Phase2EndToEndIT — **DONE** (2026-04-26)

**Status:** Implemented as
`src/test/java/com/orinuno/integration/Phase2EndToEndIT.java` and gated
behind the `e2e` Maven profile + JUnit 5 `@Tag("e2e")`.

**Coverage (4 tests, ~12s on cold MySQL container):**
- `submitFlowReachesDone` — `POST /api/v1/parse/requests` → `RequestWorker`
  claims via `SELECT … FOR UPDATE SKIP LOCKED` → mocked
  `ParserService.searchInternal` returns a seeded `KodikContent` →
  `markDone` writes `result_content_ids` → `GET /parse/requests/{id}`
  reports `status=DONE` with the saved id.
- `idempotentSubmitReturnsExistingActiveRequest` — second submit with
  identical body returns 200 (not 201) and the same id, proving the
  canonical-JSON SHA-256 idempotency key.
- `exportReadyReturnsContentWithDecodedVariants` — pre-seeded
  `kodik_content` + `kodik_episode_variant` (with `mp4_link`) shows up in
  `GET /api/v1/export/ready` with the Phase 2 metadata fields
  (`lastSeason`, `lastEpisode`, `episodesCount`) and the nested
  seasons → episodes → variants tree.
- `listLimitZeroExposesTotalCountHeader` — `GET /parse/requests?limit=0`
  returns empty rows + `X-Total-Count` header (the contract
  downstream consumer's discovery-loop relies on for backpressure).

**How to run:**
```sh
mvn test -Pe2e -Dtest=Phase2EndToEndIT
```
Default `mvn test` skips it via `excludedGroups=e2e` in surefire so the
fast unit suite stays under 5 seconds.

**Wiring exercised for real:** Spring Boot context, Testcontainers
MySQL 8 + Liquibase migrations, MyBatis mappers, HikariCP, full
WebFlux stack via `WebTestClient`, scheduled `RequestWorker` ticking on
the dedicated `orinuno-sched-*` thread pool.

**Wiring deliberately mocked:** only the outermost
`ParserService.searchInternal` boundary (so the test does not hit Kodik
or Playwright). `KodikApiClient`, decoder, token registry are loaded
with `validate-on-startup=false` and `playwright.enabled=false` so the
context boots without external dependencies.

## Parse Rate Limiting

**Priority:** Low
**Context:** `orinuno.parse.rate-limit-per-minute` is declared in config but not enforced in code. Consider adding a `RateLimiter` (e.g., Resilience4j or Bucket4j) to `ParseController`.

## ParseRequestDto Validation

**Priority:** Low
**Context:** `ParseRequestDto` accepts `@Valid` in controller but has no Bean Validation constraints. An empty request body will trigger an API call to Kodik with no search criteria. Add `@AssertTrue` or custom validator requiring at least one search field.

## GHCR Docker Image Publishing

**Priority:** Low
**Context:** Deferred during initial open-source hardening. If external contributors start running releases, add `.github/workflows/publish-image.yml` to build and push a multi-arch Docker image to `ghcr.io/samehadar/orinuno` on every tagged release. Requires enabling GHCR for the repo and wiring `GITHUB_TOKEN` with `packages: write`.

## DCO Enforcement

**Priority:** Low
**Context:** Deferred during initial open-source hardening. If/when contributions grow, install [probot/dco](https://github.com/probot/dco) to enforce `Signed-off-by:` on every commit. This is a soft legal hedge for provenance. Also requires a `CONTRIBUTING.md` update explaining `git commit -s`.

## SpotBugs — Promote to CI

**Priority:** Low
**Context:** `spotbugs-maven-plugin` is configured in `pom.xml` but only runs on demand (`mvn spotbugs:check`). The codebase currently has zero findings after applying `spotbugs-exclude.xml`, so promoting the check to `mvn verify` (and adding it to `ci.yml`) is a cheap, non-breaking upgrade. Defer until we're confident the exclusion filter hasn't hidden anything important and we have baseline findings after the next round of features.

## Pre-commit Hook for Spotless / SpotBugs

**Priority:** Low
**Discovered:** 2026-04-26 (после коммитов `feat(export): expose posterUrl ...` и
`docs(backlog): record Phase 0 live-run findings ...` — оба прошли без
автоматической проверки стиля).

**Context:** `spotless-maven-plugin` сконфигурирован с `<phase>validate</phase>`,
а `spotbugs-maven-plugin` вообще без `<executions>` и с
`<failOnError>false</failOnError>`. Это значит:

- Spotless срабатывает только на Maven-командах, проходящих через фазу
  `validate` (`mvn compile/test/package/verify/...`), и **никогда** на
  `git commit` — git ничего не запускает (см. ниже).
- SpotBugs нигде не запускается автоматически — даже `mvn verify` его не
  трогает; нужен явный `mvn spotbugs:check`, и тот не ломает билд.
- В `.git/hooks/` лежат только дефолтные `*.sample` (неактивные шаблоны
  от `git init`). `core.hooksPath` не переопределён. Husky / lefthook /
  `pre-commit` framework тоже не установлены. Поэтому `git commit` идёт
  напрямую, без какой-либо проверки.

**Следствие:** можно закоммитить и запушить код с нарушениями
google-java-format (AOSP) и/или с новыми SpotBugs-предупреждениями.
Сейчас спасает только то, что Composer обычно держит стиль, и что мы
вручную помним прогнать `mvn spotless:check` перед коммитом.

**Предлагаемое решение (один из вариантов или их комбинация):**

1. `.pre-commit-config.yaml` в корне + `pre-commit install` после клона.
   Хук вида: на staged `*.java` запускать `mvn -q spotless:apply`
   (или `spotless:check`, чтобы не молча переписывать файлы) и
   `git add -u` обратно. Стандартный путь, переносим между машинами.
2. Сырой `.git/hooks/pre-commit` в репозитории + `core.hooksPath` или
   `git-hooks-installer` script. Минимально, но требует ручной установки
   у каждого разработчика.
3. Привязать SpotBugs к фазе `verify` с `failOnError=true` — закрывает
   серверный CI-скан, но не локальные коммиты.
4. Добавить `mvn -q spotless:check spotbugs:check` шагом в
   `.github/workflows/ci.yml` — самое надёжное (CI всегда отработает),
   но обратная связь медленная (после push).

Связано с существующим "SpotBugs — Promote to CI" пунктом выше, но
шире: там только про CI-step, тут — про локальную обратную связь до
push.

**Scope:** касается только orinuno. external aggregator / downstream consumer в
этом раунде не трогаем — там и Spotless/SpotBugs не подключены, и
любая правка quality-pipeline затронет другие парсеры.

## `mp4Link='true'` Data Quality (CLOSED 2026-04-25)

**Status:** CLOSED — `KodikVideoDecoderService.parseVideoResponse` used to embed a `_geo_blocked: "true"` sentinel into the quality map whenever Kodik returned a geo-blocked placeholder. `ParserService.selectBestQuality` then happily picked the string `"true"` as the "best" quality, and the export surface returned `mp4Link: "true"` to consumers. Fixed in three places: decoder now returns an empty map on geo-block (no sentinel); `ParserService.selectBestQuality`, `VideoDownloadService.pickBestQualityUrl`, and `StreamController.pickBestQuality` all defensively skip keys starting with `_` and values that do not start with `http`. Liquibase migration `20260425010000_cleanup_invalid_mp4_link.sql` nulls out pre-existing bad rows on first boot. Covered by `KodikVideoDecoderServiceTest.parseVideoResponseReturnsEmptyForGeoBlocked` and `ParserServiceTest.selectBestQualityIgnoresSentinelsAndNonHttpValues`.

## Single-variant Decode Endpoint (CLOSED 2026-04-25)

**Status:** CLOSED — the demo UI used to call `POST /api/v1/parse/decode/{contentId}` (content-wide) when the user clicked "Decode" next to a single variant, which could hang for minutes on large anime catalogues. Added `ParserService.decodeForVariant(variantId)` and exposed it as `POST /api/v1/parse/decode/variant/{variantId}`, returning `{"variantId": Long, "decoded": Boolean}`. The frontend's `decodeSingleVariant()` now hits the variant endpoint; the content-wide endpoint remains for bulk decodes.

## Download Progress Visibility (CLOSED 2026-04-25)

**Status:** CLOSED — when Playwright timed out and the service fell back to WebClient, the UI perpetually displayed "Initializing…" because `totalSegments` stayed `null` (WebClient downloads do not segment). Two fixes landed together:

- Backend: `DownloadState` gained `expectedTotalBytes`; `DownloadProgress` tracks `expectedTotalBytes` (from `Content-Length`) and increments `totalBytes` as each `DataBuffer` is written. A fast-path in `VideoDownloadService.downloadWithStrategy` now goes straight to the CDN when `variant.mp4_link` is already decoded, cutting time-to-first-byte from ~37s (Playwright timeout) to ~2s.
- Frontend: `ContentDetailView.vue` introduced `ProgressView` with three modes (`segments`, `bytes`, `indeterminate`), a `phaseHint` (`Browser handshake`, `Direct MP4 (CDN)`, `Playwright timed out — falling back to direct MP4`), and an `elapsed` timer in every mode so stalled downloads are obvious.

## Phase 0 e2e — Cross-Repo Issues with downstream consumer (NEW 2026-04-25)

**Priority:** High (blocks full integration)
**Context:** Spent a session bringing `external Kodik parser` up locally against orinuno (with the corporate-VPN access to `<corp-registry>`). Polling cycle works (`Fetched 3 items from sourcekodik`), DTO mapping into `ExportSerialRequest` is correct, but the rest of the pipeline has five concrete defects. Full breakdown (with code refs and proposed fixes) lives in `BACKLOG.md` → section **E2E-PHASE0-FINDINGS**. Short version:

- **E2E-1** — `downstream consumer/application.yml` declares `storage.minio.*` and `meter-api.url`, but `MinioStorageProperty`/`MeterApiProperty` use `prefix = "minio"` and `prefix = "meter"`. The application can't start on a clean local box without supplying `MINIO_URL`, `MINIO_ACCESS_KEY`, `MINIO_ACCESS_SECRET`, `MINIO_DEFAULT_BUCKET_NAME`, `MINIO_DEFAULT_BASE_FOLDER`, `MINIO_DEFAULT_REGION`, `METER_BASE_URL` envs by hand. Likely masked in prod by a central ConfigMap.
- **E2E-2** — `downstream consumer/pom.xml` ships only the Liquibase Maven plugin, not `liquibase-core`, so Spring Boot autoconfig never runs migrations on startup. First DB call dies with `Table 'parser_kodik.kodik_export_state' doesn't exist`. Workaround: `mvn liquibase:update`.
- **E2E-3** — `KodikExportScheduler.processExportItem` only `upsert`s on success and `updateStatus(FAILED)` on error, but the row doesn't exist yet → `UPDATE` is a no-op → no record of failed attempts → every poll cycle retries from scratch and we lose visibility.
- **E2E-4** — Poster is taken from `screenshots[0]`, which is a frame thumbnail, not a kinopoisk poster. Plus on the test run not a single "Stored poster" log fired even though `screenshots` was non-empty in DTO — likely because downstream consumer calls `/export/ready` without `with_material_data=true`, so orinuno returns a trimmed DTO. Need to add an explicit `posterUrl` field on orinuno's `ContentExportDto` and have downstream consumer download from there.
- **E2E-5** — `Episode.filepath` and `EpisodeVariant.filepath` go to meter-api as raw, time-bombed Kodik CDN URLs (e.g. `…/720.mp4` with `:2026042514` expiry baked in). If meter-api persists them as-is, every export rots in ~24-48h. Need a contract decision: downstream consumer downloads to MinIO before exporting / meter-api proxies on demand / meter-api re-decodes from orinuno.

**Owners:** E2E-1..E2E-3 are pure external aggregator work. E2E-4 has an orinuno side (add `posterUrl` to export DTO + OpenAPI spec). E2E-5 is a contract-level discussion across both repos.

## Phase 0 e2e — Live Run (UPDATED 2026-04-26)

**Status:** Live dry-run executed against the official `the external compose stack` stack (`storage`, `storage-create-buckets`, `percona`, plus a Python HTTP stub for `meter-api` on `:8082`). orinuno boot, polling cycle, mapping into `ContentExportRequest`, MinIO write, and the freshly added retry channel were all exercised. Orinuno-side fixes (`posterUrl` on `ContentExportDto`, OpenAPI update, defensive filters in `ParserService.selectBestQuality`) and downstream consumer-side fixes (canonical `application.yml` defaults, state-machine, retry channel via `findRetryReady`) are all merged.

**What the live run covered (and what unit tests did not catch):**

- ✅ E2E-3 happy path: 7/7 ready-items go `PENDING → EXPORTED` on a clean cycle, `retry_count=0`, `last_exported_at` populated, `poster_filepath` set for the two items whose `posterUrl` is hosted on `shikimori.io`.
- ✅ E2E-3 negative path: meter stub flipped to 503 → all 7 items go `FAILED`, `retry_count=1`, `next_retry_at = now + KODIK_RETRY_BACKOFF_MS`, `error_message="Retries exhausted: 3/3"` (reactor's inner `backoff(3, 100ms)` first).
- ✅ E2E-3 back-off skip: while `next_retry_at` is in the future, the next poll tick runs `findRetryReady(now, batch)` and gets 0 rows — no spam against meter.
- ✅ E2E-3 retry recovery: after the back-off elapses and meter recovers, `findRetryReady` returns the FAILED ids, the scheduler refetches each via `getExportData(id)`, runs `processExportItem(...)`, and the rows transition `FAILED → EXPORTED` with `retry_count` reset to 0.
- ✅ E2E-4: `dto.posterUrl()` is the first source-of-truth, with `screenshots[0]` only as a logged debug fallback. On the live dataset 5/7 posters are kinopoisk URLs and 2/7 are shikimori URLs.

**New issues discovered by the live run (all logged in `BACKLOG.md` → section `E2E-PHASE0-LIVE-RUN-FINDINGS`):**

- **LIVE-1 [PUNTED → external aggregator team]** — `MeterApiService.exportContent` calls `response.success().booleanValue()` without null-check; any meter response missing the field crashes downstream consumer with NPE. Should be `Boolean.TRUE.equals(response.success())` with an explicit "contract broken" exception otherwise. **Не делаем сами**: правка в shared `meter-api-spring-boot-starter` затрагивает все парсеры (`parser-alloha`, `parser-seasonvars`, …). Передано команде external aggregator через `external Kodik parser/BACKLOG.md`.
- **LIVE-2 [DONE]** — `KodikMediaDownloader` теперь штампит `User-Agent: Mozilla/5.0 ... Chrome/135.0.0.0` и доменно-специфичный `Referer` (`kinopoisk.ru/` для yandex.net, `shikimori.one/` для shikimori, `kodik.info/` для kodik mirror) на каждый запрос постера. Перегрузка `downloadAndStorePoster(primary, fallback, id)` падает с primary на `screenshots[0]` при любой ошибке (включая 403). Покрыто `KodikMediaDownloaderTest` (5 кейсов) + обновлёнными `KodikExportSchedulerTest` (11 кейсов).
- **LIVE-3 [DONE]** — `findRetryReady` + `retryFailed()` pass + bug-fix для early-return в `pollAndExport`. FAILED-записи теперь прорабатываются по back-off расписанию.
- **LIVE-4 [DONE]** — `useGeneratedKeys="true" keyProperty="id"` убран из `<insert id="markPending">` в `KodikExportStateMapper.xml`.
- **LIVE-5 [DONE]** — Дефолты `downstream consumer/application.yml` (`MINIO_KEY=secret1234`, `MINIO_BUCKET=movies`, `DB_PASSWORD=123`) приведены к значениям `docker/docker-compose.yml`. `./mvnw spring-boot:run` стартует без env-overrides.

**E2E-5 [BLOCKED → contract decision]** — `Episode.filepath` / `EpisodeVariant.filepath` всё ещё уходят в meter как сырые временные kodik CDN URLs с зашитым `:expiry`. Не правим односторонне: нужно решение от owner-а meter-api контракта, кто хранит финальный mp4 (downstream consumer скачивает в MinIO до экспорта / meter-api проксирует / meter-api re-decode через orinuno). Записано в `external Kodik parser/BACKLOG.md` как `E2E-5` для обсуждения на синке.

## Playwright Geo-block Mitigation via Proxy Rotation

**Priority:** Medium
**Context:** `PlaywrightVideoFetcher.newStealthContext()` now applies basic anti-detection measures (UA, viewport, locale, `navigator.webdriver`, etc.), which is enough to dodge trivial headless checks. It does **not** solve IP-based geo-blocking: from Kazakhstan, for example, Kodik's player refuses to fire the video request regardless of fingerprint. We already have `kodik_proxy` and `ProxyProviderService` for the decoder path — wiring the same pool into `browser.newContext(new Browser.NewContextOptions().setProxy(...))` would let Playwright rotate through regional egress. Scoped out of the current round because the proxy pool needs a region tag first (otherwise we would randomly route through more blocked proxies). Tracked in `BACKLOG.md` as `IDEA-DOWNLOAD-PROXY`.

## PlaywrightVideoFetcher Tests are Flaky

**Priority:** Medium
**Context:** `PlaywrightVideoFetcherTest.defaultPropertiesShouldBeSensible` and `downloadVideo` still fail sporadically and are currently marked as skipped in CI. They need a stable test harness (either a mocked `BrowserContext` or a recorded-HAR replay) so Playwright assertions can run headlessly without a real Kodik fetch. Originally listed as `TD-5` in `BACKLOG.md`.

## Schema Drift — `KodikApiStabilityTest` vs DTO (CLOSED 2026-04-24)

**Status:** CLOSED — `DtoFieldExtractor` introduced so `RESULT_KNOWN_FIELDS` and `PAGINATED_KNOWN_FIELDS` are derived from the actual DTO (`KodikSearchResponse` + `KodikSearchResponse.Result`). Both the runtime detector (`KodikResponseMapper`) and the live stability test (`KodikApiStabilityTest`) now share a single source of truth, eliminating the class of bugs where the test greenlit a DTO that was missing fields (as happened with `mdl_id` + `worldart_animation_id` + `worldart_cinema_id`). Three hardcoded sets remain on purpose — they cover shapes for which we do not have typed DTOs (`material_data`, `/translations/v2` items, "simple" `{time, total, results}` envelopes from `/genres`, `/countries`, etc.).

## CodeQL Alerts — Dismissed for Review

**Priority:** Low
**Context:** Alert #1 `java/log-injection` was fixed in commit `40b0eee`. All other alerts were dismissed with explicit reasons; re-evaluate when the attack surface changes:

- **Alerts #2 / #7 (`PlaywrightVideoFetcher.remuxToMp4`), won't fix:** `ffmpeg` is resolved from the container PATH. Revisit if we ever ship as a standalone jar/executable without a controlled image.
- **Alerts #3, #4, #8, #9 (`VideoDownloadLiveIntegrationTest` ffprobe calls), won't fix:** Test-only; `ffprobe` is optional and gated by `isFfprobeAvailable()`.
- **Alerts #5, #6 (`HlsController` CSRF on GET), false positive:** Stateless API, `X-API-KEY` header auth. Revisit if we ever add cookie-based session auth (e.g., a built-in web UI that uses the API with credentials).
- **Alerts #10–14 (`KodikTokenRegistry` `java/sensitive-log`), false positive:** CodeQL flags `tokenFilePath` as sensitive because the variable name contains "token", but the value is a `java.nio.file.Path` pointing to `data/kodik_tokens.json` — a filesystem path, not a secret. All real token values in this class are logged exclusively through `mask()` (see lines 350, 401, 421). Revisit if the registry ever starts embedding token values directly into log arguments without `mask()`.

**Note:** Alerts #7, #8, #9 are duplicates of #2, #3, #4 — CodeQL re-raised them after the Spotless AOSP reformat (`80ffb8c`) and SpotBugs fix (`9ba7128`) shifted line numbers. This is expected: dismissals in CodeQL are scoped to a specific (file, line) fingerprint, not a semantic fingerprint, so pure formatting commits can cause re-flagging.
