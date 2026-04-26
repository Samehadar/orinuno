---
title: Testing
description: Four test suites — unit, e2e, live integration, and API stability — plus how to run each one in isolation.
---

Orinuno has four test suites. Most of the time you will only need the unit
suite, which has no external dependencies. The other three require either
a valid Kodik token, Docker for Testcontainers, or both.

## Unit tests

No Kodik token, no database, no network.

```sh
mvn test
```

What they cover:

- `KodikVideoDecoderServiceTest` — ROT cipher across shifts, Base64 decode,
  full decode pipeline.
- `ParserServiceTest` — parsing and persistence logic.
- `ContentControllerTest` — REST controller through
  `WebTestClient.bindToController`.
- `ExportDataServiceTest` — structured export DTO assembly.
- `ProxyProviderServiceTest` — round-robin rotation.
- `OpenSourceGuardTest` — asserts there are no imports from private
  projects. Keeps the standalone boundary clean.

## Phase 2 e2e test (Testcontainers)

`Phase2EndToEndIT` boots the full Spring context against a Testcontainers
MySQL 8 container and exercises the parse-request log + export-ready
flow end-to-end (HTTP → MyBatis → MySQL → scheduled `RequestWorker` →
HTTP). Only the outermost `ParserService.searchInternal` boundary is
mocked, so no Kodik token or network access is required.

```sh
mvn test -Pe2e -Dtest=Phase2EndToEndIT
```

Default `mvn test` skips it via `excludedGroups=e2e` so the unit suite
stays fast (≈4s). The e2e run takes ~12s on a warm MySQL image.

What it covers:

- `POST /api/v1/parse/requests` → `RequestWorker.tick()` claims via
  `SELECT … FOR UPDATE SKIP LOCKED` → `markDone` writes
  `result_content_ids` → `GET /parse/requests/{id}` reports `DONE`.
- Idempotent re-submit returns the same id (canonical-JSON SHA-256).
- `GET /api/v1/export/ready` returns the seeded content with the
  Phase 2 metadata fields (`lastSeason`, `lastEpisode`,
  `episodesCount`) and the nested seasons/episodes/variants tree.
- `GET /parse/requests?limit=0` exposes `X-Total-Count` (the
  contract parser-kodik's discovery loop reads for backpressure).

## Live integration tests

Uses a real Kodik token and a Testcontainers MySQL.

```sh
KODIK_TOKEN=your_token mvn test -Dtest=KodikLiveIntegrationTest
```

Runs a full end-to-end cycle: search → save → list → decode → export.

## API stability tests

37 assertions against the real Kodik API. Validates response shapes and
detects schema drift in CI. Does not need a database — pure HTTP.

```sh
KODIK_TOKEN=your_token mvn test -Dtest=KodikApiStabilityTest
```

Coverage per endpoint:

| Endpoint | Tests | What is validated |
| --- | --- | --- |
| `/search` | 18 | title (ru/en/ja), shikimori_id, kinopoisk_id, imdb_id, types, with_seasons, translation_type, camrip, year, strict, not_blocked_in, genres, limit=1, nonexistent, invalid token |
| `/search` + material_data | 3 | Ratings, numeric vs list fields, film (Matrix) |
| `/list` | 8 | sort × order combos, types, pagination, with_material_data, year, subtitles |
| `/translations/v2` | 3 | Structure, known voices (AniLibria), type (voice/subtitles) |
| `/genres` | 3 | Structure, genre presence, genres_type filter |
| `/countries` | 2 | Structure, country presence |
| `/years` | 2 | Structure, year presence (2023/2024) |
| `/qualities/v2` | 2 | Structure, quality presence |

Each test includes schema drift detection: response keys are compared
against the known DTO field set. Unknown keys are logged as
`[SCHEMA DRIFT]` in stderr but do not fail the test. A missing required
field (`id`, `link`, `translation`) fails the test loud.

## Run everything that needs the token

```sh
KODIK_TOKEN=your_token mvn test -Dtest="KodikApiStabilityTest,KodikLiveIntegrationTest"
```

## Run unit-only (no token, no Docker)

```sh
mvn test -Dtest='!KodikApiStabilityTest,!KodikLiveIntegrationTest,!VideoDownloadLiveIntegrationTest'
```

## Related

- [Code style](/orinuno/development/code-style/)
- [Contributing](/orinuno/development/contributing/)
