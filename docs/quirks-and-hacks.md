# Quirks & Hacks

This file is the **tribal-knowledge log** of orinuno: every non-obvious workaround, every unwritten Kodik API rule, every "why is the code shaped this way" question.

When you discover a quirk in Kodik (or any other source) that forced us to write surprising code, **add an entry here in the same commit**. Future-you will thank present-you.

## Entry template

```markdown
## YYYY-MM-DD — <one-line title> [<area>]

**Symptom**: what breaks when you don't apply the workaround.
**Root cause**: where the surprise lives (which Kodik endpoint / which JS file / which CDN).
**Workaround**: what we do.
**Where in code**: link(s) to the file(s).
**Verified by**: pointer to a test or live probe that catches a regression.
**Discovered via**: how we found out (research session, user report, alert, etc).
**Related**: ADR / BACKLOG / external repo.
```

The `[<area>]` tag lets you grep: `[api]`, `[decoder]`, `[player-js]`, `[ttl]`, `[token]`, `[geo]`, `[cdn]`, `[hls]`, `[playwright]`, `[scheduler]`, `[liquibase]`, `[testing]`, etc.

---

## 2026-05-02 — Kodik is geo-fenced: CIS works, UK/US/EU IPs see degraded behaviour [geo] [cdn] [decoder]

**Symptom**: An identical request that returns `200 + {"links":...}` from a Kazakhstan or Russia IP returns one of:

- `403 Forbidden` (rare, IP banned outright)
- `200` with `iframe.html` redirecting to a "your country is not supported" page
- `200` with `links` populated but every URL inside them resolves to Kodik's "geo-block" edge proxy (see `GeoBlockDetector`)
- HTTP 5xx with cryptic errors

…when called from a UK / US / EU / non-CIS IP.

**Root cause**: Kodik's licensing model is centred on the CIS market. Their CDN nodes refuse to issue real CDN URLs to non-CIS callers; the legitimate API still answers, but the URLs it hands out are dummies that point to a "go away" page. This is independent of the token — even a valid token gets dummy URLs from outside CIS.

**Workaround**:

- The decoder integration tests run against Kodik's live endpoints and **MUST be executed from a CIS IP** (KZ, RU, BY, KG). Without that, you'll see "broken" tests that aren't actually broken.
- `GeoBlockDetector` (existing) inspects every decoded URL and returns an empty `links` map if every URL routes to the geo-block edge proxy. This guards against silently storing dummy URLs.
- Production deployments either run inside CIS infrastructure or use a CIS proxy pool (`KodikProxy` table — proxy-core integration).

**Where in code**:

- [`GeoBlockDetector`](../orinuno-app/src/main/java/com/orinuno/service/GeoBlockDetector.java) — IP-detect on decoded URLs.
- [`ProxyWebClientService`](../orinuno-app/src/main/java/com/orinuno/service/ProxyWebClientService.java) — proxy fallback.

**Verified by**: `KodikLiveIntegrationTest` documents the requirement; `KodikDecoderRawProbeTest` notes its own limitation in the class Javadoc.

**Discovered via**: research probe 2026-05-02 — first run against UK VPN returned dummy URLs even with a valid token; switching to KZ VPN (109.248.32.206, Karagandy) immediately returned real CDN URLs.

**Related**: [BACKLOG.md](../BACKLOG.md) → DECODE-7 (handle geo-block as first-class), proxy-core docs.

---

## 2026-05-02 — Kodik accepts both GET and POST on every public endpoint [api]

**Symptom**: nothing breaks today, but reading our code or upstream OSS clients raises the question "why POST with all params in the query string?"

**Root cause**: Kodik's documentation only shows POST examples, but in practice the request handler is method-agnostic. Live probe against `kodik-api.com` (2026-05-02, see `docs/research/2026-05-02-api-and-decoder-probe.md`) with a valid token shows:

| Endpoint           | GET | POST | bodies match? |
|--------------------|-----|------|---------------|
| `/search?title=…`  | 200 | 200  | YES           |
| `/list`            | 200 | 200  | YES           |
| `/translations/v2` | 200 | 200  | YES           |
| `/genres`          | 200 | 200  | YES           |
| `/countries`       | 200 | 200  | YES           |
| `/years`           | 200 | 200  | YES           |
| `/qualities/v2`    | 200 | 200  | YES           |

`/search` without any filter parameter returns **HTTP 500** with body `{"error":"Не указан хотя бы один параметр для поиска"}` on **both methods** — meaning Kodik uses HTTP 500 to signal application-level errors instead of 400/422. This is its own quirk (see entry below).

**Workaround**: we keep POST for parity with the three reference clients we follow (kodik-api Rust, kodikwrapper TS, AnimeParsers Python). POST is also the documented method, so if Kodik ever locks down GET we are in the larger user pool. GET is reserved as an emergency fallback only — see [ADR 0002](adr/0002-kodik-http-method-policy.md).

**Where in code**: [`KodikApiClient.searchRaw()`](../orinuno-app/src/main/java/com/orinuno/client/KodikApiClient.java) and friends use `WebClient.post()`.

**Verified by**: [`KodikHttpMethodProbeTest`](../orinuno-app/src/test/java/com/orinuno/client/KodikHttpMethodProbeTest.java) re-runs the probe against live API when `KODIK_TOKEN` is set.

**Discovered via**: research probe 2026-05-02.

**Related**: ADR 0002, [BACKLOG.md → API-3](../BACKLOG.md).

---

## 2026-05-02 — Kodik returns HTTP 500 for application-level errors, not 4xx [api]

**Symptom**: A "missing token" or "missing search filter" returns `500 Internal Server Error` with a JSON body, not the expected `401 Unauthorized` or `400 Bad Request`. Generic HTTP middlewares (retry-on-5xx, circuit breakers) treat these as transient and retry pointlessly.

**Root cause**: Kodik's PHP-style error handling lifts every business-validation error to HTTP 500. Verified live:

| Request                                          | Status | Body                                                   |
|--------------------------------------------------|--------|--------------------------------------------------------|
| Any endpoint without `token=`                    | 500    | `{"error":"Отсутствует или неверный токен"}`           |
| `/search` with no filter (`title`, etc.)         | 500    | `{"error":"Не указан хотя бы один параметр для поиска"}` |
| `/search?types=film` (unknown enum)              | 500    | `{"error":"Неправильный тип"}`                         |

**Workaround**:

- `KodikApiClient` parses the body even on `500` to distinguish transient (network/CDN) from permanent (token-expired, bad-input) errors.
- `KodikTokenLifecycle` treats `500 + "Отсутствует или неверный токен"` as a token-DEAD signal (not a transport error).
- We do **not** add 5xx to a generic retry list — only specific status+body combos.

**Where in code**: token-DEAD detection lives in [`KodikTokenLifecycle`](../orinuno-app/src/main/java/com/orinuno/token/KodikTokenLifecycle.java) and `KodikTokenRegistry`.

**Verified by**: existing live integration tests assert specific 500-bodies, e.g. `KodikLiveIntegrationTest`.

**Discovered via**: research probe 2026-05-02 (cross-checked against AnimeParsers' `_get_token_from_kodik` workaround).

**Related**: BACKLOG.md → DECODE-7 (handle 500 with body).

---

## 2026-05-02 — Decoder POST body must be `application/x-www-form-urlencoded` (not JSON) [decoder] [post-body]

**Symptom**: If you send `Content-Type: application/json` to `/ftor` (or any video-info endpoint), Kodik responds **`404 with an HTML page`** (router-level rejection) instead of the 200 you'd expect. If you send `Content-Type: application/x-www-form-urlencoded` but with a malformed body, you get a misleading **`500 + {"error":"Отсутствует или неверный токен"}`** even when the token in `urlParams` is valid — clue wrong: it's not a token error, it's a `d_sign`/`pd_sign` (stale signed iframe params) error.

**Root cause**: `/ftor`, `/kor`, `/gvi`, `/seria` (the dynamic video-info endpoints) discriminate at TWO layers: the router rejects non-form Content-Types with 404 before reaching app code, and the app then validates `d_sign`/`pd_sign` (signed-iframe params; expire in ~24h) before issuing a 500 with the misleading error.

Boolean values in the form body must be the **string** literals `"true"` / `"false"`, not JSON booleans (which couldn't be sent anyway since JSON Content-Type is rejected).

Live evidence (2026-05-02, from KZ IP):

| Request body                                                                            | Status | Body kind                       |
|-----------------------------------------------------------------------------------------|--------|---------------------------------|
| `application/x-www-form-urlencoded` + body with REAL urlParams (signed `d_sign`/`pd_sign`) | 200    | JSON `{"links":..., "default":...}` |
| `application/x-www-form-urlencoded` + body with FAKE/missing signed params               | 500    | HTML error page                 |
| `application/x-www-form-urlencoded` + body where Kodik recognizes shape but signed params are stale | 500 | JSON `{"error":"Отсутствует или неверный токен"}` (misleading) |
| `application/json` + ANY body                                                            | 404    | HTML 404 page (router-level)    |
| No `Content-Type` header                                                                  | 404    | HTML 404 page                   |

**Workaround**: see [`KodikVideoDecoderService.sendVideoRequest()`](../orinuno-app/src/main/java/com/orinuno/service/KodikVideoDecoderService.java):

```java
formData.add("bad_user", "false");
formData.add("cdn_is_working", "true");
```

— string `"false"` / `"true"`, **not** Java `boolean`. `MediaType.APPLICATION_FORM_URLENCODED` is hard-coded.

**Where in code**: [`KodikVideoDecoderService.sendVideoRequest()`](../orinuno-app/src/main/java/com/orinuno/service/KodikVideoDecoderService.java) lines around `formData.add("bad_user", ...)`.

**Verified by**: [`KodikVideoDecoderServiceTest`](../orinuno-app/src/test/java/com/orinuno/service/KodikVideoDecoderServiceTest.java) (form-encoding); live decoder test in `KodikLiveIntegrationTest`.

**Discovered via**: research probe 2026-05-02. Same trick documented in kodikwrapper (TS) and AnimeParsers (Python).

**Related**: [ADR 0003](adr/0003-kodik-decoder-post-body-format.md).

---

## 2026-05-02 — Player JS file naming is now type-dependent (NOT just `app.player_*.js`) [decoder] [player-js]

**Symptom**: For iframes of type `seria` (anime serials, foreign serials), the regex in `KodikVideoDecoderService.PLAYER_JS_PATTERN` returns `null`, decoder errors with `Player JS path not found in iframe`, the whole decode fails, the variant is left with `mp4_link IS NULL` and added to the failed-decode retry queue. The retry loop never recovers because the regex is stable across re-tries.

**Root cause**: as of (at least) 2026-05-02, Kodik serves DIFFERENT player-js files depending on `vInfo.type`:

| iframe type     | URL pattern                       | actual file                                                |
|-----------------|-----------------------------------|------------------------------------------------------------|
| `video` (movie) | `/video/<id>/<hash>/<q>p`         | `assets/js/app.player_single.<sha>.js`                     |
| `seria`         | `/serial/<id>/<hash>/<q>p`        | `assets/js/app.serial.<sha>.js`                            |
| (legacy)        | (any)                             | `assets/js/app.player_<variant>.<sha>.js`                  |

The old regex `src="/(assets/js/app\.player_[^"]+\.js)"` matches `player_single.<sha>.js` (group 1 = `single`) but does **not** match `serial.<sha>.js`.

Both files contain the same `type:"POST",url:atob("L2Z0b3I=")` snippet — i.e. the POST URL is `/ftor` regardless of file. So the fix is purely about **finding the file**, not about parsing it.

**Workaround**: broaden the regex to `app\.(player_[^.]+|serial)(?:\.[^.]+)*\.js` (or simpler: any `app\.[a-z_]+\.[^"]+\.js` under `/assets/js/`). Keep `cachedVideoInfoPath` as the safety net.

**Where in code**: [`KodikVideoDecoderService.PLAYER_JS_PATTERN`](../orinuno-app/src/main/java/com/orinuno/service/KodikVideoDecoderService.java).

**Verified by**: [`KodikVideoDecoderRegexTest`](../orinuno-app/src/test/java/com/orinuno/service/KodikVideoDecoderRegexTest.java) — regression test that fails on `app.serial.*` HTML if regex is reverted.

**Discovered via**: research probe 2026-05-02 (loaded 4 different iframe types, observed naming variance).

**Related**: BACKLOG.md → DECODE-7.

---

## 2026-05-02 — Decoder response is HLS-only (`application/x-mpegURL`); MP4 quality maxes out at 720p [decoder] [hls] [quality]

**Symptom**: We assume `mp4_link` is an MP4 — name says so. In reality, every link Kodik decodes is an HLS playlist (`.m3u8`), and the highest available quality observed in 10 random titles (anime + movies) is **720p**. There is no `1080`/`2160` quality bucket in the `links` map.

**Root cause**: Kodik's CDN is built around `application/x-mpegURL` (HLS) for adaptive streaming. The `default` field in the `/ftor` response is always `360` (Kodik's bandwidth-conservative pick for the embedded player), not the best quality.

Sample raw response shape (truncated):

```json
{
  "advert_script": "",
  "domain": "kodik.cc",
  "default": 360,
  "links": {
    "240": [{"src": "<rot13+b64>", "type": "application/x-mpegURL"}],
    "360": [{"src": "...", "type": "application/x-mpegURL"}],
    "480": [{"src": "...", "type": "application/x-mpegURL"}],
    "720": [{"src": "...", "type": "application/x-mpegURL"}]
  },
  "vast": [...],
  "reserve_vast": [...],
  "ip": "152.233.28.109"
}
```

Quality distribution observed in 10 titles (Naruto, FMA, AoT, OP, Steins;Gate, Ninja Avengers, Armitage, Interstella, Joker):

- 5 titles: `360, 480, 720` (3 qualities)
- 4 titles: `240, 360, 480, 720` (4 qualities)
- 0 titles: any `1080` or `2160`

**Workaround**:

- `selectBestQuality` picks **max numeric key**, ignoring `default` (which is biased low).
- The "rename `mp4_link` column" idea is parked — see ADR 0004.
- The "rewrite `720p` → `1080p` in URL" trick (used by some Telegram bots) is a no-op on Kodik, since 1080p simply doesn't exist on the CDN. Confirmed by HEAD-probing forced-quality URLs (returns the 720p file).

**Where in code**: [`ParserService.selectBestQuality()`](../orinuno-app/src/main/java/com/orinuno/service/ParserService.java).

**Verified by**: live tests in `KodikVideoDecoderServiceTest` use realistic quality maps.

**Discovered via**: research probe 2026-05-02.

**Related**: [ADR 0004](adr/0004-kodik-decoder-quality-strategy.md), BACKLOG.md → DECODE-1, DECODE-7.4.

---

## 2026-05-02 — Decoder video-info path is netloc-dependent (per-netloc cache) [decoder] [cache]

**Symptom**: Before DECODE-7, a single global `cachedVideoInfoPath` AtomicReference held the last successful POST path. If iframes from different netlocs (e.g. `kodikplayer.com`, `kodik.cc`, `kodikv.cc`) ever resolved to different paths, the cache would flap on every alternation, causing 50% of decodes to fall back to brute-force retries.

**Root cause**: Kodik runs multiple distribution netlocs and is free to ship slightly different player JS bundles to each. Today both `kodikplayer.com` and `kodik.cc` resolve to `/ftor`, but the cache was a single slot for all netlocs — risk of a silent regression any day Kodik rebalances its CDN.

**Workaround**: cache is now `ConcurrentMap<String, String>` keyed by netloc (lower-cased host of the iframe URL). Entries appear lazily on first successful POST per netloc; reads fall through to the default fallback path `/ftor` when the netloc is new.

**Where in code**: [`DecoderPathCache`](../orinuno-app/src/main/java/com/orinuno/service/decoder/DecoderPathCache.java) — wired into [`KodikVideoDecoderService.resolveVideoInfoPath()`](../orinuno-app/src/main/java/com/orinuno/service/KodikVideoDecoderService.java).

**Verified by**: [`KodikVideoDecoderRegexTest`](../orinuno-app/src/test/java/com/orinuno/service/KodikVideoDecoderRegexTest.java) regression suite and `KodikVideoDecoderCacheTest` (per-netloc invariants), `DecoderPathCacheTest` (DECODE-2 hydration + persistence).

**Discovered via**: DECODE-7 robustness pass; persistence layer added in DECODE-2.

**Related**: BACKLOG.md → DECODE-7, DECODE-2.

---

## 2026-05-02 — Calendar deltas are watched + persisted as an outbox, not polled [calendar] [scheduler] [outbox]

**Symptom**: Until CAL-6 the `/api/v1/calendar` endpoint was query-only — every call returned a fresh snapshot of Kodik's `dumps.kodikres.com/calendar.json`, but nobody noticed when an anime's `next_episode` advanced, when `status` flipped from `ongoing` → `released`, or when `score` changed. Consumers that wanted to react to those changes had to download the full ~few-MB dump and diff it themselves on every poll cycle.

**Root cause**: Kodik refreshes the calendar dump every few minutes; the dump is the only authoritative signal for "this episode just dropped" / "this anime is now finished". There's no event stream from Kodik.

**Workaround** (CAL-6): a `CalendarDeltaWatcher` Spring bean runs on a 5-minute schedule (default; configurable via `orinuno.calendar.delta-watcher.poll-interval-minutes`). On every cycle it (1) does a conditional GET via `KodikCalendarHttpClient` (cheap when no upstream change), (2) bulk-loads the matching `kodik_calendar_state` rows for every Shikimori id in the fetch, (3) computes deltas in memory, (4) writes one row per delta to `kodik_calendar_outbox` and upserts the state row. Idempotent: re-running the same fetch produces zero new events.

The watcher is **disabled by default** so deployments without the CAL-6 Liquibase migration stay green; flip `orinuno.calendar.delta-watcher.enabled=true` after migration. Outbox is exposed at `GET /api/v1/calendar/outbox?since=…&limit=…` (default since=epoch, limit=200, max=1000).

**Where in code**: [`CalendarDeltaWatcher`](../orinuno-app/src/main/java/com/orinuno/service/calendar/CalendarDeltaWatcher.java), [`CalendarDeltaScheduler`](../orinuno-app/src/main/java/com/orinuno/service/calendar/CalendarDeltaScheduler.java), Liquibase scripts `20260502030000_create_kodik_calendar_state.sql` + `20260502030100_create_kodik_calendar_outbox.sql`, `KodikCalendarController#outbox`.

**Verified by**: [`CalendarDeltaWatcherTest`](../orinuno-app/src/test/java/com/orinuno/service/calendar/CalendarDeltaWatcherTest.java) — 13 tests covering NEW_ANIME / NEXT_EPISODE_ADVANCED / EPISODES_AIRED_INCREASED / STATUS_CHANGED / SCORE_CHANGED / RELEASED_ON_SET, idempotency on re-run, score rounding, and missing-anime-id absorption.

**Discovered via**: BACKLOG → CAL-6.

**Related**: DUMP-1 (calendar dump HEAD-poll), Calendar service (cached query layer).

---

## 2026-05-02 — Decoder path cache survives JVM restarts via `kodik_decoder_path_cache` [decoder] [persistence] [scheduler]

**Symptom**: Every restart of orinuno re-paid the brute-force discovery cost for every Kodik netloc (parse player JS, fall back to `/ftor`/`/kor`/`/gvi`/`/seria` chain). On a fleet of decoders this multiplied the load against Kodik on every deploy and added a multi-second cold-decode latency for every distinct netloc.

**Root cause**: Until DECODE-2 the per-netloc cache from DECODE-7 was a JVM-local `ConcurrentHashMap`. JVM-local caches are great for raw speed but lose all state across restarts.

**Workaround** (DECODE-2): a Spring bean [`DecoderPathCache`](../orinuno-app/src/main/java/com/orinuno/service/decoder/DecoderPathCache.java) wraps the in-memory map AND an optional MyBatis repository. On `@PostConstruct` it hydrates the in-memory map from `kodik_decoder_path_cache`; on every `put` it fires off a non-blocking `Schedulers.boundedElastic()` upsert. DB failures (no MySQL, transient outage, schema mismatch) are absorbed with a warning — the in-memory map remains the source of truth so the decoder never blocks on the DB. Visible at `GET /api/v1/health/decoder/path-cache`.

**Where in code**: [`DecoderPathCache`](../orinuno-app/src/main/java/com/orinuno/service/decoder/DecoderPathCache.java), [`KodikDecoderPathCacheRepository`](../orinuno-app/src/main/java/com/orinuno/repository/KodikDecoderPathCacheRepository.java), Liquibase script `20260502020000_create_kodik_decoder_path_cache.sql`.

**Verified by**: [`DecoderPathCacheTest`](../orinuno-app/src/test/java/com/orinuno/service/decoder/DecoderPathCacheTest.java) — covers hydration, async persistence, and absorption of DB failures.

**Discovered via**: BACKLOG → DECODE-2.

**Related**: DECODE-7 (in-memory per-netloc cache).

---

## 2026-05-02 — `vInfo.type` enum is `video` or `seria` (NOT `movie`/`film`) [decoder] [api]

**Symptom**: New code that constructs an iframe URL by hand or pattern-matches on the type field thinks "movie" or "film" are valid values; debugging logs miss matches.

**Root cause**: Kodik's iframe HTML emits `vInfo.type = '<lit>'` where `<lit>` is one of:

- `video` — for everything filed under `/video/<id>/<hash>/...` (films, foreign-movies, russian-movies, foreign-cartoons, russian-cartoons, soviet-cartoons).
- `seria` — for everything filed under `/serial/<id>/<hash>/...` (anime-serials, foreign-serials, russian-serials, multi-part movies).

Note `seria`, not `serial` or `series`. Note `video`, not `movie`.

Separately, the search/list API has its own `types` filter taxonomy (`russian-movie`, `foreign-cartoon`, `anime-serial`, etc.) which IS NOT the same as `vInfo.type`. `types=film` and `types=movie` are rejected with `{"error":"Неправильный тип"}`.

**Workaround**: when constructing decoder requests, use the literal `vInfo.type` string from the iframe — never derive it from search-API `type` field.

**Where in code**: [`KodikVideoDecoderService.processIframe()`](../orinuno-app/src/main/java/com/orinuno/service/KodikVideoDecoderService.java) — extracted via `TYPE_PATTERN`.

**Verified by**: existing decoder unit tests; new `KodikVideoDecoderRegexTest` includes both type fixtures.

**Discovered via**: research probe 2026-05-02.

**Related**: ADR 0002 (HTTP method probe), kodik-api Rust enum coverage IDEA-RUST-1.

---

## 2026-05-02 — `types` literals are hyphenated; only 9 values exist [api] [dto]

**Symptom**: A typo in the `types` query/form param (e.g. `anime_serial` with underscore, `animeserial`, `film`, `movie`) returns HTTP 500 `{"error":"Неправильный тип"}` from Kodik. There is no warning — the entire request is rejected.

**Root cause**: Kodik enforces a closed enum of nine types: `anime-serial`, `anime-movie`, `foreign-serial`, `foreign-movie`, `russian-serial`, `russian-movie`, `russian-cartoon`, `foreign-cartoon`, `soviet-cartoon`. They are all hyphenated, never underscored, and `film` / `movie` are not aliases.

**Workaround**: API-7 introduces [`KodikType`](../orinuno-app/src/main/java/com/orinuno/client/dto/KodikType.java) enum and fluent builder shortcuts on `KodikSearchRequest` / `KodikListRequest` / `KodikReferenceRequest`:

```java
KodikSearchRequest req = KodikSearchRequest.builder().anime().title("Naruto").build();
KodikListRequest lr = KodikListRequest.builder().movies().limit(50).build();
KodikReferenceRequest rr = KodikReferenceRequest.builder().type(KodikType.ANIME_SERIAL).build();
```

The String-typed `.types("...")` setter is preserved for backward compatibility, but the enum path is recommended — it makes typos a compile-time error.

**Where in code**: [`KodikType`](../orinuno-app/src/main/java/com/orinuno/client/dto/KodikType.java); custom Lombok builder extensions inside `KodikSearchRequest`, `KodikListRequest`, `KodikReferenceRequest`.

**Verified by**: [`KodikTypeTest`](../orinuno-app/src/test/java/com/orinuno/client/dto/KodikTypeTest.java), [`KodikRequestBuilderShortcutsTest`](../orinuno-app/src/test/java/com/orinuno/client/dto/KodikRequestBuilderShortcutsTest.java).

**Discovered via**: API-3 / API-7 implementation passes.

**Related**: BACKLOG.md → API-7.

---

## 2026-05-02 — User-Agent strings live in one place [http] [stealth]

**Symptom**: Before UA-1, the same desktop User-Agent literal lived in five files (decoder service, CDN WebClient bean, token auto-discovery, Playwright stealth context, Playwright HLS segment fetch). Bumping Chrome's major version meant a five-file PR; introducing a Firefox tier required auditing every site; and the segment-fetch UA had silently drifted to a shorter/older variant than the rest.

**Root cause**: Plain copy-paste over time. There was no single owner for "what UA do we send to Kodik?" so whoever added the next caller pasted whatever string was nearby.

**Workaround**: [`RotatingUserAgentProvider`](../orinuno-app/src/main/java/com/orinuno/client/http/RotatingUserAgentProvider.java) is the single source of truth. It exposes:

- `randomDesktop()` — per-request rotation across the canonical pool (5 modern Chrome / Firefox strings on Win / macOS / Linux). Used by the decoder for iframe + player JS + POST and by token auto-discovery.
- `stableDesktop()` — sticky per-process pick, used where session affinity matters (Playwright contexts, the CDN WebClient bean, HLS segment fetcher).
- `orinunoBot(suffix)` — honest "I am orinuno" UA for endpoints operated by Kodik for legitimate consumers (currently the calendar dump).

To rotate the pool, edit `DESKTOP_POOL` in `RotatingUserAgentProvider` and bump the comment date — that's the single audit point.

**Where in code**: [`RotatingUserAgentProvider`](../orinuno-app/src/main/java/com/orinuno/client/http/RotatingUserAgentProvider.java) and its callers (`KodikVideoDecoderService`, `KodikTokenAutoDiscovery`, `WebClientConfiguration#kodikCdnWebClient`, `PlaywrightVideoFetcher`, `KodikCalendarHttpClient`).

**Verified by**: [`RotatingUserAgentProviderTest`](../orinuno-app/src/test/java/com/orinuno/client/http/RotatingUserAgentProviderTest.java).

**Discovered via**: UA-1 audit pass.

**Related**: BACKLOG.md → UA-1.

---

## 2026-05-02 — Kodik publishes huge raw dumps; we HEAD-poll, never auto-download [dumps] [scheduler]

**Symptom**: A naïve "GET films.json on a schedule and reparse" loop will pull ~82 MB of JSON every poll. With three dumps (calendar 80 KB / serials 175 MB / films 82 MB) and an hourly cadence that's ~6 GB / day of egress for Kodik AND of inbound for orinuno — for data that almost never changes between polls.

**Root cause**: `https://dumps.kodikres.com/{calendar,serials,films}.json` are full re-publications, not deltas. The bodies are large and there is no incremental endpoint.

**Workaround**: DUMP-1 introduces `KodikDumpService` and `DumpScheduler` that:

- Send `HEAD` requests by default — cheap, returns ETag / Last-Modified / Content-Length.
- Persist per-dump state (`orinuno_dump_state` table) keyed by `dump_name` (UNIQUE).
- Bump `last_changed_at` only when the headers actually differ from the last poll.
- Track `consecutive_failures` so the `/api/v1/health/dumps` endpoint can degrade.
- Default `orinuno.dumps.enabled=false` — opt-in. `download-body` is a separate flag (`orinuno.dumps.download-body`, also default `false`) reserved for DUMP-2's bootstrap path.

We deliberately do NOT wire up the full body download in DUMP-1 — the persisted state alone is enough for "is the dump stale?" alerts and for downstream consumers that wake up on `lastChangedAt` advances.

**Where in code**: [`KodikDumpService`](../orinuno-app/src/main/java/com/orinuno/service/dumps/KodikDumpService.java), [`DumpScheduler`](../orinuno-app/src/main/java/com/orinuno/service/dumps/DumpScheduler.java), [`HealthController#dumpsHealth`](../orinuno-app/src/main/java/com/orinuno/controller/HealthController.java), Liquibase changeset `20260502010000_create_orinuno_dump_state.sql`.

**Verified by**: [`KodikDumpServiceTest`](../orinuno-app/src/test/java/com/orinuno/service/dumps/KodikDumpServiceTest.java) (URL building, disabled-config short-circuit, persist-failure consecutive-failures bump, snapshot delegation, value-object shape).

**Discovered via**: orinuno-radar `dumps.yml` audit + DUMP-1 implementation pass.

**Related**: BACKLOG.md → DUMP-1, DUMP-2, DUMP-3; orinuno-radar `tracking/dumps.yml`.

---

## 2026-05-02 — Bootstrapping from a 175 MB dump must stream, not load [dumps] [memory]

**Symptom**: `ObjectMapper.readValue(stream, new TypeReference<List<Result>>(){})` on `serials.json` allocates ~700 MB of heap (`List<Result>` × 50k entries × Lombok-generated objects). The JVM either OOMs or pauses for 30+ seconds in GC.

**Root cause**: The dump is published as a single top-level JSON array (`[ {...}, {...}, ... ]`). DOM-style parsing materialises the entire array.

**Workaround**: DUMP-2 introduces [`KodikDumpStreamingReader`](../orinuno-app/src/main/java/com/orinuno/service/dumps/KodikDumpStreamingReader.java) which uses Jackson's pull-parser (`JsonFactory.createParser` + `parser.nextToken()` + `OBJECT_MAPPER.readValue(parser, Result.class)`) to walk the array one element at a time. Resident memory is bounded by the largest single entry (~10 KB worst case for serials with rich `material_data` blobs).

The companion [`KodikDumpBootstrapService`](../orinuno-app/src/main/java/com/orinuno/service/dumps/KodikDumpBootstrapService.java) wires the reader to the existing `EntityFactory` + `ContentService` upsert path, so the bootstrap path inherits the production idempotency rules (kinopoisk-id-keyed lookup, COALESCE upsert that never overwrites a valid mp4_link).

**Safety gate**: bootstrap is gated behind two flags — `orinuno.dumps.enabled` (top-level) AND `orinuno.dumps.download-body` (specifically permits multi-GB GETs). Both default to `false`. Operators flip both, run the bootstrap, then flip both back.

**Per-element failures are absorbed**: a single malformed entry (Kodik occasionally publishes shapes the SDK doesn't model) is logged + counted in the `skipped` field of the `BootstrapResult` but does NOT abort the stream. Ingesting 99.9% of the catalogue beats failing on the 0.1% edge case.

**Where in code**: [`KodikDumpStreamingReader`](../orinuno-app/src/main/java/com/orinuno/service/dumps/KodikDumpStreamingReader.java), [`KodikDumpBootstrapService`](../orinuno-app/src/main/java/com/orinuno/service/dumps/KodikDumpBootstrapService.java).

**Verified by**: [`KodikDumpStreamingReaderTest`](../orinuno-app/src/test/java/com/orinuno/service/dumps/KodikDumpStreamingReaderTest.java) (4 tests), [`KodikDumpBootstrapServiceTest`](../orinuno-app/src/test/java/com/orinuno/service/dumps/KodikDumpBootstrapServiceTest.java) (5 tests including the "consumer-throws-mid-stream" regression).

**Discovered via**: DUMP-2 implementation pass + `films.json` shape probe.

**Related**: BACKLOG.md → DUMP-2; admin endpoint to trigger bootstrap is intentionally NOT shipped in this commit (DUMP-2 ships the capability; the trigger is a follow-up).

---

## 2026-05-02 — Decode pipeline is regex-first / Playwright-sniff fallback (DECODE-8) [decoder] [playwright] [resilience]

**Symptom**: Every time Kodik ships a fresh player JS bundle that breaks the regex (`PLAYER_JS_PATTERN`, `VIDEO_INFO_POST_PATTERN`, etc.) the decoder silently emits `{}` for every variant until we ship a regex hot-fix. In the May 2026 incident the new `app.serial.*.js` rename took us roughly 6 hours to notice + patch; in the meantime ~3 600 episodes failed to decode and the retry-failed scheduler kept churning the same set without any new attempt strategy.

**Root cause**: The regex/JS path is a fast happy path that depends on Kodik's player bundle staying shape-stable. Kodik has no obligation to preserve that shape and we have no way to subscribe to changes — it's a "wait until everything's broken, then patch" relationship.

**Workaround** (DECODE-8): the decode call goes through [`KodikDecodeOrchestrator`](../orinuno-app/src/main/java/com/orinuno/service/decoder/KodikDecodeOrchestrator.java) which:

1. Always tries the regex/JS [`KodikVideoDecoderService`](../orinuno-app/src/main/java/com/orinuno/service/KodikVideoDecoderService.java) first (cheap, ~few hundred ms, no Chromium tab).
2. If the regex returns `Map.of()` OR errors OR completes empty, falls back to [`PlaywrightSniffDecoder`](../orinuno-app/src/main/java/com/orinuno/service/decoder/PlaywrightSniffDecoder.java) — opens the player in headless Chromium via the existing `PlaywrightVideoFetcher` and intercepts the CDN URL from the network.
3. Records the chosen path in the `decode_method` column of `kodik_episode_variant` (`REGEX` / `SNIFF` / null = pre-DECODE-8 row) so we can SQL-trend "how often is regex breaking".
4. Records the same on a Prometheus counter (`KodikDecoderMetrics#recordDecodeMethod`).

The orchestrator NEVER errors out — both decoders return an empty bucket on failure so the caller in `ParserService.persistDecode()` can persist `null` mp4_link + bump retry counter without distinguishing "the URL fetch failed" from "the decoder is broken".

**Sniff result shape**: Playwright captures whatever CDN URL the browser fetches, which is almost always the master HLS manifest. The sniff decoder tags it under quality key `"auto"` (intentionally NON-numeric) so `ParserService#pickBestQualityEntry` ignores it during the "best numeric quality" scan and we fall through to the "first non-empty bucket" branch.

**Safety gates**:

- `orinuno.decoder.sniff-fallback-enabled` (default `false`) — opt-in because Playwright is heavyweight (full Chromium + ~20 s per sniff) and may not be installed on every deployment.
- `orinuno.playwright.enabled` (default `true` only when Playwright is wired) — independent gate; if Playwright failed to initialise the orchestrator silently skips the sniff branch.
- `Mono.empty()` and `null` map from the regex decoder are both treated as "empty" by `defaultIfEmpty(Map.of())` + the explicit null-map filter — defensive against future regex refactors that might forget to emit a value.

**Where in code**: [`KodikDecodeOrchestrator`](../orinuno-app/src/main/java/com/orinuno/service/decoder/KodikDecodeOrchestrator.java), [`PlaywrightSniffDecoder`](../orinuno-app/src/main/java/com/orinuno/service/decoder/PlaywrightSniffDecoder.java), [`DecodeAttemptResult`](../orinuno-app/src/main/java/com/orinuno/service/decoder/DecodeAttemptResult.java), [`DecodeMethod`](../orinuno-app/src/main/java/com/orinuno/service/decoder/DecodeMethod.java), [`ParserService#persistDecode`](../orinuno-app/src/main/java/com/orinuno/service/ParserService.java), Liquibase script `20260502040000_add_decode_method_to_episode_variant.sql`.

**Verified by**: [`KodikDecodeOrchestratorTest`](../orinuno-app/src/test/java/com/orinuno/service/decoder/KodikDecodeOrchestratorTest.java) (8 tests — short-circuit on success, fallback on empty/error, sniff-disabled config gate, sniff-unavailable gate, defensive empty-Mono handling, metrics) and [`PlaywrightSniffDecoderTest`](../orinuno-app/src/test/java/com/orinuno/service/decoder/PlaywrightSniffDecoderTest.java) (6 tests — null fetcher, unavailable fetcher, error swallowing, blank-URL filter).

**Discovered via**: BACKLOG → DECODE-8 + the May 2026 `app.serial.*.js` regex breakage post-mortem.

**Related**: DECODE-7 (regex robustness), DECODE-2 (persistent path cache).

---
