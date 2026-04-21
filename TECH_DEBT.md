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
