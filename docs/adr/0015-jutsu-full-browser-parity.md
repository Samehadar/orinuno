# ADR 0015 — jut.su SDK: full browser parity (catalog, search, anime info, episode meta, notice feed) + drift detection

- **Status**: Accepted
- **Date**: 2026-05-04
- **Deciders**: orinuno maintainers
- **Related**: ADR 0001 (Kodik SDK extraction pilot, drift detector pattern), ADR 0009 (PLAYER-4 jut.su decoder), ADR 0012 (jut.su SDK extraction), ADR 0014 (controllers on SDK facades), [BACKLOG.md → IDEA-AP-2](../../BACKLOG.md)

## Context

Up to ADR 0014 the `jutsu-sdk` module exposed exactly one user-facing capability: `JutsuClient.decode(url)` — fetch an episode page, extract one mp4 URL per quality bucket, return a `JutsuDecodeResult`. Everything else a real consumer of jut.su needs (catalog, search, anime info, episode metadata, upcoming releases) had to be scraped client-side, or simply was not available from orinuno at all.

Three things forced a wider scope:

1. **Real consumers want catalogue parity, not just decoding.** Downstream agents (a Telegram release-feed bot, a multi-source aggregator) need to walk the catalog, filter it by genre/type/year/sort, search by title, fetch full anime info with all seasons, peek at single-episode metadata without decoding, and read the homepage "upcoming releases" feed. Asking each consumer to re-implement all of that on top of bare HTTP would re-create the same DLE-cookie / rate-limit / charset / drift problems we already solved once for the decoder.

2. **No drift safety net for HTML scraping.** Unlike Kodik's JSON API (covered by [`kodik-sdk-drift`](../../kodik-sdk-drift)), jut.su has no contract — it's plain HTML rendered by a CMS that occasionally changes class names, adds promo blocks, or tweaks URL patterns (e.g. the Boruto `/{slug}/{N}/{M}.html` variant we hit while writing tests). A silent regression in the catalog parser today would surface only as "no new anime in the last week" much later. The Kodik SDK already established the pattern (`DriftDetector` + `DriftEvent` + a strict-mode replay test that asserts zero drift events against captured fixtures); it should be reused, not reinvented.

3. **Symmetry + a single facade.** Adding three more clients (`Catalog…`, `Info…`, `Notice…`) without weaving them into `JutsuClient` would force every consumer to know about Spring beans, rate limiters, and session managers individually. We want exactly one entry point per source, mirroring what `kodikwrapper` and `AnimeParsers` already give their users.

## Decision

Extend `jutsu-sdk` with five new sibling subpackages — `filter/`, `catalog/`, `info/`, `episode/`, `notice/` — plus a centralised `drift/` package that all parsers share. Wire them all behind the existing `JutsuClient` facade so consumers keep using one bean. Reuse the existing `JutsuRateLimiter` + `JutsuSessionManager` as shared singletons across every SDK subsystem.

### Module layout (new pieces only)

```
jutsu-sdk/src/main/java/com/orinuno/jutsu/
├─ catalog/
│   ├─ JutsuCatalogClient.java
│   ├─ JutsuCatalogEntry.java
│   ├─ JutsuCatalogFilter.java
│   ├─ JutsuCatalogPage.java
│   ├─ JutsuCatalogParser.java
│   └─ JutsuCatalogRequest.java
├─ drift/
│   ├─ JutsuDriftDetector.java
│   ├─ JutsuDriftEvent.java
│   ├─ JutsuDriftException.java
│   ├─ JutsuDriftHealth.java
│   ├─ JutsuDriftSignal.java
│   ├─ JutsuDriftSnapshot.java
│   └─ JutsuParserContext.java
├─ episode/
│   ├─ JutsuEpisodeMeta.java
│   ├─ JutsuEpisodeMetaClient.java
│   ├─ JutsuEpisodePageParser.java
│   ├─ JutsuFilmMeta.java                ← 2026-05-08 follow-up
│   └─ JutsuPageMeta.java                ← 2026-05-08 follow-up (sealed)
├─ filter/
│   ├─ JutsuFilterFormParser.java
│   ├─ JutsuFilterSlugger.java
│   ├─ JutsuGenre.java
│   ├─ JutsuSort.java
│   ├─ JutsuType.java
│   └─ JutsuYear.java
├─ info/
│   ├─ JutsuAnimeInfo.java
│   ├─ JutsuAnimeInfoClient.java
│   ├─ JutsuAnimeInfoParser.java
│   └─ JutsuSeason.java
├─ notice/
│   ├─ JutsuNoticeClient.java
│   ├─ JutsuNoticeEntry.java
│   ├─ JutsuNoticeFeed.java
│   └─ JutsuNoticeParser.java
└─ JutsuClient.java                 ← facade now exposes ten user-facing operations
```

### Operations exposed on `JutsuClient`

| Method | Subpackage | Purpose |
|---|---|---|
| `decode(url)` | `decoder/` (existing) | Existing single-episode mp4 extraction. |
| `browseCatalog(request)` | `catalog/` | `POST /anime/` paginated catalog (with `start_from_page=N` AJAX shape). Filter is composed deterministically as a URL slug. |
| `searchByTitle(title, page)` | `catalog/` | Title search via `show_search` form field, orthogonal to filters. |
| `getAnimeInfo(slug)` | `info/` | `GET /{slug}/` — full anime info, including all seasons + all episodes (green = available, black = premium-gated). |
| `getEpisodeMeta(url)` | `episode/` | `GET …/episode-N.html` **or** `GET …/film-N.html` — lightweight metadata without actually decoding the player. Returns a sealed `JutsuPageMeta` (`JutsuEpisodeMeta` for episode shapes, `JutsuFilmMeta` for full-length-film shapes). See the *2026-05-08 follow-up* below. |
| `getNoticeFeed(noticeId)` | `notice/` | `POST /engine/ajax/site_notice.php` — one page of upcoming-releases feed. |
| `getLatestNoticeFeed()` | `notice/` | Scrapes the homepage to discover the latest cursor, then calls `getNoticeFeed`. |
| `walkNoticeFeedsBackwards(start)` | `notice/` | Pagination as a `Flux<JutsuNoticeFeed>`. |
| `streamNoticeEntries(start)` | `notice/` | Flattened `Flux<JutsuNoticeEntry>` for NDJSON streaming consumers. |
| `getDriftSnapshot()` | `drift/` | Lifetime + last-event drift snapshot for dashboards / source rankers. |

### Filter slug composition

`JutsuFilterSlugger` composes the URL path slug deterministically. Enum-by-enum order is fixed (`type → genres → year → sort`), `BY_RATING` is elided (jut.su's default sort is "by rating", and adding `/sort_by_rating/` is treated as drift), and the slug round-trips through `parse → toString → parse`. Exhaustive unit tests (~1000 randomised cases) prove the round-trip property.

### Drift detection

A single `JutsuDriftDetector` is constructed once per `JutsuClient` and shared across all parsers via `JutsuParserContext`. Each parser raises `JutsuDriftSignal` events through the context (typed: `MISSING_FIELD`, `NEW_FIELD`, `URL_PATTERN_MISMATCH`, `EMPTY_RESPONSE`, `SCHEMA_VIOLATION`). The detector keeps a thread-safe rolling counter + last-N events buffer. `JutsuDriftSnapshot` exposes lifetime totals and a `JutsuDriftHealth` enum (`HEALTHY`, `DEGRADED`, `BROKEN`).

Two complementary modes:

1. **Lenient mode (production)**: the parser logs the signal, makes the most reasonable assumption, and returns whatever it could extract. The user gets best-effort data; the dashboard learns about the drift.
2. **Strict mode (tests + canary)**: any drift event is escalated to `JutsuDriftException`. `JutsuStrictReplayTest` re-runs every parser against its captured fixture in strict mode and asserts zero events. This is the regression net that catches a parser regression *before* it ships.

### Spring wiring + canary probe

`orinuno-app/JutsuSdkConfiguration` instantiates one of each: `JutsuRateLimiter`, `JutsuSessionManager`, `JutsuDriftDetector`. They're injected into the `JutsuClient` builder so the SDK reuses the same singletons (no double-counted RPS, no parallel detectors).

`JutsuDriftScheduledProbe` (`@Scheduled`, `@ConditionalOnProperty`) calls a canary subset (catalog page 1, a known-stable anime info page, the latest notice feed) every `orinuno.providers.jutsu.drift-probe.interval-minutes` minutes in lenient mode, so the detector keeps a fresh signal even during low traffic. Probe failures are logged and swallowed; the goal is observation, not retry.

### Multi-source ranking integration

`MultiSourceController` reads `JutsuClient.getDriftSnapshot().health()` before invoking `MultiSourceRanker`. If the snapshot is anything but `HEALTHY`, jut.su is added to `RankingPreferences.demotedProviders`, and `MultiSourceRanker.providerScore()` collapses its score to `0.0`. jut.su still appears in the response (so consumers see the option exists) but it lands at the bottom of the rank order. When the probe restores `HEALTHY`, the demotion clears automatically on the next request.

### REST surface (`orinuno-app`)

A unified `JutsuApiController` exposes the SDK under `/api/v1/sources/jutsu/`:

- `GET /catalog?type=…&genre=…&year=…&sort=…&page=N`
- `GET /search?title=…&page=N`
- `GET /anime/{slug}`
- `GET /episode?url=…`
- `GET /notice?noticeId=…` and `GET /notice` (latest)
- `GET /notice/stream?startNoticeId=…` (NDJSON stream of `JutsuNoticeEntryDto`)
- `GET /drift` (snapshot)

`SourcesController.capabilities()` advertises all six new ops + the live drift signal in `GET /api/v1/sources` so dashboards see source health in a single round trip.

## Consequences

### Wins

- **Single-bean parity with `kodikwrapper` / `AnimeParsers`.** A consumer wires one `JutsuClient` and gets the full jut.su browser model — catalog, search, info, episode meta, notice feed, decoder, drift snapshot.
- **Rate limiter + session manager stay shared.** Adding four new clients didn't multiply outbound RPS or DLE login traffic; everything flows through the same Bucket4j bucket and the same sticky cookies.
- **Drift visible early.** Strict-mode replay tests fail on the first PR that breaks a parser against its captured fixture, instead of silently returning empty pages in production.
- **Graceful degradation on drift.** When jut.su HTML changes, the ranker auto-demotes it instead of returning broken results; `/api/v1/sources` shows the degraded health to operators.
- **Composable filters that round-trip.** `JutsuFilterSlugger` is a pure function with exhaustive tests, so any future change to filter shape is caught immediately.

### Costs

- **Six new packages and ~30 new public classes in the SDK.** Mitigated by the strict subpackage boundaries (`filter/`, `catalog/`, etc.) and by the fact that `JutsuClient` is still the single recommended entry point.
- **One scheduled probe always running.** Off by default in tests, on in production. The probe is rate-limited like any other request and logs every cycle, so the cost is observable and bounded.
- **Cross-layer imports in `JutsuApiController`.** It imports DTOs that wrap nine SDK records. Acceptable: that file's job is exactly to translate SDK shapes to REST shapes.

### Risks

- **Fixture rot.** The strict replay tests are only as good as the captured HTML. We mitigate by keeping fixtures small + dated + linked to the live URL they were captured from, and by re-capturing them whenever the canary probe flags drift in production for more than 24 hours.
- **Hidden assumption that DLE cookies authenticate every endpoint.** Currently true — all five endpoints use the same session. If jut.su ever splits authentication between subdomains, `JutsuSessionManager` will need a per-host cookie jar (recorded as TD on the SDK README, not blocking).

## Blocked on

Nothing — Step 17 ships alongside the surrounding orchestration (this PR cycle).

## Tracker

| Item | Status |
|------|--------|
| `drift/` core: detector, signal, event, snapshot, health, parser context, exception + 1st-party tests | ✅ done |
| `filter/` enums + slugger + form parser + ~1000-case round-trip property test | ✅ done |
| `catalog/` request, entry, page, parser, client + paginated-AJAX wiring | ✅ done |
| `info/` anime info, season, parser (green + black episode buttons), client | ✅ done |
| `episode/` meta, parser, client + canonical-URL drift check | ✅ done |
| `notice/` feed, entry, parser (with `/{slug}/{N}/{M}.html` variant), client (incl. backward walk + NDJSON stream) | ✅ done |
| `JutsuStrictReplayTest` — every parser at zero drift events on captured fixtures | ✅ done |
| `JutsuClient` facade exposes ten ops; builder accepts shared rate limiter / session manager / drift detector | ✅ done |
| `JutsuLiveIntegrationTest` (5 blocks) gated by `JUTSU_LIVE_TESTS=1` | ✅ done |
| `JutsuApiController` + DTOs + WebTestClient tests | ✅ done |
| `JutsuDriftScheduledProbe` (`@Scheduled`, `@ConditionalOnProperty`) | ✅ done |
| `MultiSourceRanker.demotedProviders` + auto-demote on `health != HEALTHY` | ✅ done |
| `SourcesController.capabilities()` advertises six new ops + driftHealth + driftLifetimeEvents | ✅ done |
| Reactor regression: `mvn -pl orinuno-app -am test` | ✅ 611 passed, 68 skipped, 0 failed |
| ADR 0015 + cross-links in jutsu-sdk/README, AGENTS.md, BACKLOG.md | ✅ done |

## Follow-up (2026-05-08): full-length films

`life-no-game/film-1.html` (and any other entry where jut.su attaches a movie to a series) exposed two gaps in the original ADR:

1. **`info/JutsuAnimeInfoParser`** dropped film anchors because the URL pattern matched only `/{slug}/(season-N/)?episode-M.html`. Result: `JutsuAnimeInfo` returned `totalEpisodeCount=12`, films absent — both from `/api/v1/sources/jutsu/anime/{slug}` and the demo.
2. **`episode/JutsuEpisodePageParser`** crashed `/api/v1/sources/jutsu/episode?url=…/film-N.html` with a 500: the canonical didn't match `episode-N.html`, parser returned `null`, client threw `IllegalStateException`.

We did **not** overload `season=0` as a film sentinel; instead films became a sibling of episodes:

- New record `info/JutsuFilmListing(slug, index, label, url)` and `JutsuAnimeInfo.films()` / `totalFilmCount()`. The parser now performs a single anchor walk and classifies each anchor by URL pattern (episode vs film); cross-promo film links to other anime stay out of the current entry's list.
- New L1 table `jutsu_film` (`slug`, `film_index`, `label`, `relative_url`, `paywalled`, `discovered_at`, `last_seen_at`) with FK to `jutsu_title`. `JutsuCatalogSyncService.runNoticeWalkOnce` upserts films via `infoToFilms` alongside `infoToEpisodes`; `JutsuCatalogReadService.findAnimeInfo` reads them via `JutsuFilmRepository`.
- New sealed `episode/JutsuPageMeta permits JutsuEpisodeMeta, JutsuFilmMeta`. `JutsuClient.getEpisodeMeta(url)` is now `Mono<JutsuPageMeta>`; the parser dispatches on the canonical regex (`episode-N.html` vs `film-N.html`) and now also raises `SCHEMA_VIOLATION` on a silent kind-flip between the requested and canonical URL.
- REST: `/api/v1/sources/jutsu/episode` returns a discriminated `JutsuPageMetaDto` (`oneOf JutsuEpisodeMetaDto | JutsuFilmMetaDto`, Jackson `@JsonTypeInfo(property = "kind")`); consumers switch on `kind: "episode" | "film"` before pattern-matching the payload. OpenAPI snapshot regenerated.

Verified end-to-end against `life-no-game` (1 film) and `onepuunchman` (0 films, no regression). Commits: `3294d3a` (anime info + films table), `67a73e7` (page-meta sealed dto + 500 fix).
