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

## CodeQL Alerts — Dismissed for Review

**Priority:** Low
**Context:** Initial CodeQL scan flagged 6 alerts. #1 `java/log-injection` was fixed (commit `40b0eee`). The others were dismissed with explicit reasons; re-evaluate when the attack surface changes:

- **Alerts #2 (`PlaywrightVideoFetcher.remuxToMp4`), won't fix:** `ffmpeg` is resolved from the container PATH. Revisit if we ever ship as a standalone jar/executable without a controlled image.
- **Alerts #3, #4 (`VideoDownloadLiveIntegrationTest` ffprobe calls), won't fix:** Test-only; `ffprobe` is optional and gated by `isFfprobeAvailable()`.
- **Alerts #5, #6 (`HlsController` CSRF on GET), false positive:** Stateless API, `X-API-KEY` header auth. Revisit if we ever add cookie-based session auth (e.g., a built-in web UI that uses the API with credentials).
