# Technical Debt

## PF-I7: Async Jobs for Long-Running Decode Operations

**Priority:** Medium
**Context:** Decoding mp4 links for large anime serials (e.g., Naruto with 220 episodes * N translations) can take hours. The current API is synchronous -- the HTTP connection stays open for the entire duration.

**Proposed solution:**
- `POST /api/v1/parse/search` returns a `jobId` immediately instead of blocking
- `GET /api/v1/jobs/{jobId}` returns job status and progress (percentage, current step)
- Jobs stored in a database table with states: `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`
- Background thread pool processes jobs sequentially

**Reference:** KinoPub parser in backend-master uses RabbitMQ + State Machine for this. For a standalone service, a simpler REST-based job polling pattern is preferred.

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

**Scope:** касается только orinuno. backend-master / parser-kodik в
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

## Phase 0 e2e — Cross-Repo Issues with parser-kodik (NEW 2026-04-25)

**Priority:** High (blocks full integration)
**Context:** Spent a session bringing `backend-master/parser-kodik` up locally against orinuno (with the corporate-VPN access to `harbor.dats.tech`). Polling cycle works (`Fetched 3 items from sourcekodik`), DTO mapping into `ExportSerialRequest` is correct, but the rest of the pipeline has five concrete defects. Full breakdown (with code refs and proposed fixes) lives in `BACKLOG.md` → section **E2E-PHASE0-FINDINGS**. Short version:

- **E2E-1** — `parser-kodik/application.yml` declares `storage.minio.*` and `meter-api.url`, but `MinioStorageProperty`/`MeterApiProperty` use `prefix = "minio"` and `prefix = "meter"`. The application can't start on a clean local box without supplying `MINIO_URL`, `MINIO_ACCESS_KEY`, `MINIO_ACCESS_SECRET`, `MINIO_DEFAULT_BUCKET_NAME`, `MINIO_DEFAULT_BASE_FOLDER`, `MINIO_DEFAULT_REGION`, `METER_BASE_URL` envs by hand. Likely masked in prod by a central ConfigMap.
- **E2E-2** — `parser-kodik/pom.xml` ships only the Liquibase Maven plugin, not `liquibase-core`, so Spring Boot autoconfig never runs migrations on startup. First DB call dies with `Table 'parser_kodik.kodik_export_state' doesn't exist`. Workaround: `mvn liquibase:update`.
- **E2E-3** — `KodikExportScheduler.processExportItem` only `upsert`s on success and `updateStatus(FAILED)` on error, but the row doesn't exist yet → `UPDATE` is a no-op → no record of failed attempts → every poll cycle retries from scratch and we lose visibility.
- **E2E-4** — Poster is taken from `screenshots[0]`, which is a frame thumbnail, not a kinopoisk poster. Plus on the test run not a single "Stored poster" log fired even though `screenshots` was non-empty in DTO — likely because parser-kodik calls `/export/ready` without `with_material_data=true`, so orinuno returns a trimmed DTO. Need to add an explicit `posterUrl` field on orinuno's `ContentExportDto` and have parser-kodik download from there.
- **E2E-5** — `Episode.filepath` and `EpisodeVariant.filepath` go to meter-api as raw, time-bombed Kodik CDN URLs (e.g. `…/720.mp4` with `:2026042514` expiry baked in). If meter-api persists them as-is, every export rots in ~24-48h. Need a contract decision: parser-kodik downloads to MinIO before exporting / meter-api proxies on demand / meter-api re-decodes from orinuno.

**Owners:** E2E-1..E2E-3 are pure backend-master work. E2E-4 has an orinuno side (add `posterUrl` to export DTO + OpenAPI spec). E2E-5 is a contract-level discussion across both repos.

## Phase 0 e2e — Live Run (UPDATED 2026-04-26)

**Status:** Live dry-run executed against the official `backend-master/docker/docker-compose.yml` stack (`storage`, `storage-create-buckets`, `percona`, plus a Python HTTP stub for `meter-api` on `:8082`). orinuno boot, polling cycle, mapping into `ContentExportRequest`, MinIO write, and the freshly added retry channel were all exercised. Orinuno-side fixes (`posterUrl` on `ContentExportDto`, OpenAPI update, defensive filters in `ParserService.selectBestQuality`) and parser-kodik-side fixes (canonical `application.yml` defaults, state-machine, retry channel via `findRetryReady`) are all merged.

**What the live run covered (and what unit tests did not catch):**

- ✅ E2E-3 happy path: 7/7 ready-items go `PENDING → EXPORTED` on a clean cycle, `retry_count=0`, `last_exported_at` populated, `poster_filepath` set for the two items whose `posterUrl` is hosted on `shikimori.io`.
- ✅ E2E-3 negative path: meter stub flipped to 503 → all 7 items go `FAILED`, `retry_count=1`, `next_retry_at = now + KODIK_RETRY_BACKOFF_MS`, `error_message="Retries exhausted: 3/3"` (reactor's inner `backoff(3, 100ms)` first).
- ✅ E2E-3 back-off skip: while `next_retry_at` is in the future, the next poll tick runs `findRetryReady(now, batch)` and gets 0 rows — no spam against meter.
- ✅ E2E-3 retry recovery: after the back-off elapses and meter recovers, `findRetryReady` returns the FAILED ids, the scheduler refetches each via `getExportData(id)`, runs `processExportItem(...)`, and the rows transition `FAILED → EXPORTED` with `retry_count` reset to 0.
- ✅ E2E-4: `dto.posterUrl()` is the first source-of-truth, with `screenshots[0]` only as a logged debug fallback. On the live dataset 5/7 posters are kinopoisk URLs and 2/7 are shikimori URLs.

**New issues discovered by the live run (all logged in `BACKLOG.md` → section `E2E-PHASE0-LIVE-RUN-FINDINGS`):**

- **LIVE-1 [PUNTED → backend-master team]** — `MeterApiService.exportContent` calls `response.success().booleanValue()` without null-check; any meter response missing the field crashes parser-kodik with NPE. Should be `Boolean.TRUE.equals(response.success())` with an explicit "contract broken" exception otherwise. **Не делаем сами**: правка в shared `meter-api-spring-boot-starter` затрагивает все парсеры (`parser-alloha`, `parser-seasonvars`, …). Передано команде backend-master через `backend-master/parser-kodik/BACKLOG.md`.
- **LIVE-2 [DONE]** — `KodikMediaDownloader` теперь штампит `User-Agent: Mozilla/5.0 ... Chrome/135.0.0.0` и доменно-специфичный `Referer` (`kinopoisk.ru/` для yandex.net, `shikimori.one/` для shikimori, `kodik.info/` для kodik mirror) на каждый запрос постера. Перегрузка `downloadAndStorePoster(primary, fallback, id)` падает с primary на `screenshots[0]` при любой ошибке (включая 403). Покрыто `KodikMediaDownloaderTest` (5 кейсов) + обновлёнными `KodikExportSchedulerTest` (11 кейсов).
- **LIVE-3 [DONE]** — `findRetryReady` + `retryFailed()` pass + bug-fix для early-return в `pollAndExport`. FAILED-записи теперь прорабатываются по back-off расписанию.
- **LIVE-4 [DONE]** — `useGeneratedKeys="true" keyProperty="id"` убран из `<insert id="markPending">` в `KodikExportStateMapper.xml`.
- **LIVE-5 [DONE]** — Дефолты `parser-kodik/application.yml` (`MINIO_KEY=secret1234`, `MINIO_BUCKET=movies`, `DB_PASSWORD=123`) приведены к значениям `docker/docker-compose.yml`. `./mvnw spring-boot:run` стартует без env-overrides.

**E2E-5 [BLOCKED → contract decision]** — `Episode.filepath` / `EpisodeVariant.filepath` всё ещё уходят в meter как сырые временные kodik CDN URLs с зашитым `:expiry`. Не правим односторонне: нужно решение от owner-а meter-api контракта, кто хранит финальный mp4 (parser-kodik скачивает в MinIO до экспорта / meter-api проксирует / meter-api re-decode через orinuno). Записано в `backend-master/parser-kodik/BACKLOG.md` как `E2E-5` для обсуждения на синке.

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
