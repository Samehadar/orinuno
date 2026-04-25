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

## `mp4Link='true'` Data Quality (CLOSED 2026-04-25)

**Status:** CLOSED — `KodikVideoDecoderService.parseVideoResponse` used to embed a `_geo_blocked: "true"` sentinel into the quality map whenever Kodik returned a geo-blocked placeholder. `ParserService.selectBestQuality` then happily picked the string `"true"` as the "best" quality, and the export surface returned `mp4Link: "true"` to consumers. Fixed in three places: decoder now returns an empty map on geo-block (no sentinel); `ParserService.selectBestQuality`, `VideoDownloadService.pickBestQualityUrl`, and `StreamController.pickBestQuality` all defensively skip keys starting with `_` and values that do not start with `http`. Liquibase migration `20260425010000_cleanup_invalid_mp4_link.sql` nulls out pre-existing bad rows on first boot. Covered by `KodikVideoDecoderServiceTest.parseVideoResponseReturnsEmptyForGeoBlocked` and `ParserServiceTest.selectBestQualityIgnoresSentinelsAndNonHttpValues`.

## Single-variant Decode Endpoint (CLOSED 2026-04-25)

**Status:** CLOSED — the demo UI used to call `POST /api/v1/parse/decode/{contentId}` (content-wide) when the user clicked "Decode" next to a single variant, which could hang for minutes on large anime catalogues. Added `ParserService.decodeForVariant(variantId)` and exposed it as `POST /api/v1/parse/decode/variant/{variantId}`, returning `{"variantId": Long, "decoded": Boolean}`. The frontend's `decodeSingleVariant()` now hits the variant endpoint; the content-wide endpoint remains for bulk decodes.

## Download Progress Visibility (CLOSED 2026-04-25)

**Status:** CLOSED — when Playwright timed out and the service fell back to WebClient, the UI perpetually displayed "Initializing…" because `totalSegments` stayed `null` (WebClient downloads do not segment). Two fixes landed together:

- Backend: `DownloadState` gained `expectedTotalBytes`; `DownloadProgress` tracks `expectedTotalBytes` (from `Content-Length`) and increments `totalBytes` as each `DataBuffer` is written. A fast-path in `VideoDownloadService.downloadWithStrategy` now goes straight to the CDN when `variant.mp4_link` is already decoded, cutting time-to-first-byte from ~37s (Playwright timeout) to ~2s.
- Frontend: `ContentDetailView.vue` introduced `ProgressView` with three modes (`segments`, `bytes`, `indeterminate`), a `phaseHint` (`Browser handshake`, `Direct MP4 (CDN)`, `Playwright timed out — falling back to direct MP4`), and an `elapsed` timer in every mode so stalled downloads are obvious.

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
