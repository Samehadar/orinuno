# jutsu-sdk

Standalone reactive Java client for [jut.su](https://jut.su).

This module is the **second per-source SDK** extracted out of the [orinuno](../README.md) project (after [`kodik-sdk-drift`](../kodik-sdk-drift)). After ADR 0015 it offers **full browser parity** with jut.su, not just episode decoding:

- **DLE authentication** with sticky cookies (`JutsuSessionManager`) — fetches the homepage POST shape, parses the four DLE-flavoured cookies, refreshes them on TTL expiry or explicit invalidation.
- **1 RPS hard cap** outbound to `jut.su` (`JutsuRateLimiter`) — single Bucket4j bucket, hot-swappable RPS via a `DoubleSupplier`, reactive `Mono.delay`-based suspension (no thread blocking). Shared across every subpackage so adding clients does not multiply outbound traffic.
- **Episode-page decoder** (`decoder/JutsuDecoder`) — fetches the episode page with the cached cookies, recognises the `tab_need_plus` overlay + `pixel.png` placeholder URLs (premium gate), classifies Cloudflare challenges and bot-detection responses with their own error codes, and returns one mp4 URL per quality bucket.
- **Catalog browser** (`catalog/JutsuCatalogClient` + `filter/JutsuFilterSlugger`) — paginated `POST /anime/` with composable filters (genre, type, year, sort) and orthogonal title search via `show_search`. Filter slug round-trips through `parse → toString → parse` (~1000-case property test).
- **Anime info** (`info/JutsuAnimeInfoClient`) — `GET /{slug}/` returns the full season list + every episode (green-coloured = available, black-coloured = premium-gated), plus the `films` list (full-length movies attached to the entry, anchors `/{slug}/film-N.html` rendered under jut.su's `<h2 class="films_title">` block) and `totalFilmCount()`.
- **Page metadata** (`episode/JutsuEpisodeMetaClient`) — `GET …/episode-N.html` **or** `GET …/film-N.html` returns a sealed `JutsuPageMeta` (`JutsuEpisodeMeta` for episodes, `JutsuFilmMeta` for full-length films) with slug / titles / thumbnail / `premiumGated` and kind-specific navigation (`prevEpisodeUrl`/`nextEpisodeUrl` vs `prevFilmUrl`/`nextFilmUrl`) without actually decoding the player.
- **Upcoming releases feed** (`notice/JutsuNoticeClient`) — `POST /engine/ajax/site_notice.php` paginated cursor, including a backward walk (`Flux<JutsuNoticeFeed>`) and a flattened `Flux<JutsuNoticeEntry>` for NDJSON streaming.
- **Schema-drift detection** (`drift/`) — every parser raises typed `JutsuDriftSignal` events through a thread-safe `JutsuDriftDetector`. Lenient mode keeps best-effort parsing alive in production; **strict mode** (used by `JutsuStrictReplayTest` + the `orinuno-app` canary probe) escalates any signal to `JutsuDriftException` so a parser regression fails CI before it ships.

It is intentionally Spring-Boot-free: the only Spring dependency is `spring-webflux` (for `WebClient`) and `spring-context` (transitively required by WebClient on Spring 6). No `@Component`, no auto-configuration, no `@ConfigurationProperties` — consumers wire the SDK manually.

## Quick start

```xml
<dependency>
    <groupId>com.orinuno</groupId>
    <artifactId>jutsu-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
JutsuClient client = JutsuClient.builder()
        .config(JutsuConfig.builder()
                .credentials(System.getenv("JUTSU_USERNAME"),
                             System.getenv("JUTSU_PASSWORD"))
                .userAgent("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                         + "AppleWebKit/537.36 (KHTML, like Gecko) "
                         + "Chrome/147.0.0.0 Safari/537.36")
                .rateLimitRps(1.0)
                .sessionTtl(Duration.ofMinutes(240))
                .loginTimeout(Duration.ofSeconds(15))
                .build())
        .build();

JutsuDecodeResult result = client.decode("https://jut.su/naruto/episode-1.html").block();
if (result.success()) {
    result.qualities().forEach((quality, mp4Url) ->
            System.out.printf("%s\t%s%n", quality, mp4Url));
} else {
    System.err.println("error: " + result.errorCode());
}
```

### Other operations

```java
// Browse the anime catalog with filters
JutsuCatalogPage page = client.browseCatalog(
        JutsuCatalogRequest.builder()
                .filter(JutsuCatalogFilter.builder()
                        .type(JutsuType.SERIES)
                        .genre(JutsuGenre.ACTION)
                        .year(JutsuYear.year(2024))
                        .sort(JutsuSort.NEWEST_FIRST)
                        .build())
                .page(2)
                .build())
        .block();

// Title search composes orthogonally with filters
JutsuCatalogPage hits = client.searchByTitle("naruto", 1).block();

// Full anime info incl. seasons + episodes
JutsuAnimeInfo info = client.getAnimeInfo("naruto").block();

// Lightweight page meta (no decode). Returns the sealed JutsuPageMeta —
// downcast to JutsuEpisodeMeta or JutsuFilmMeta depending on URL grammar.
JutsuPageMeta page = client
        .getEpisodeMeta("https://jut.su/naruto/episode-1.html")
        .block();
if (page instanceof JutsuEpisodeMeta ep) {
    System.out.printf("S%dE%d %s%n", ep.season(), ep.episode(), ep.displayTitle());
}

// Full-length-film URLs are accepted by the same method.
JutsuPageMeta filmPage = client
        .getEpisodeMeta("https://jut.su/life-no-game/film-1.html")
        .block();
if (filmPage instanceof JutsuFilmMeta film) {
    System.out.printf("F%d %s%n", film.filmIndex(), film.displayTitle());
}

// Latest "upcoming releases" notice feed page
JutsuNoticeFeed latest = client.getLatestNoticeFeed().block();

// Stream the entire historical feed as one Flux of entries
client.streamNoticeEntries(latest.requestedNoticeId())
        .doOnNext(entry -> System.out.println(entry.title()))
        .blockLast();

// Live drift snapshot for dashboards / orchestrators
JutsuDriftSnapshot snapshot = client.getDriftSnapshot();
System.out.println(snapshot.health() + " events=" + snapshot.lifetimeEvents());
```

## Configuration

All configuration is done via the `JutsuConfig` builder. Defaults match the production-tested values used by `orinuno-app`:

| Field | Default | Purpose |
|-------|---------|---------|
| `baseUrl` | `https://jut.su` | Base URL for jut.su. Override only when proxying through a mirror. |
| `username` / `password` | `null` | DLE credentials. When unset, the SDK runs in anonymous mode and premium episodes return `JUTSU_PREMIUM_REQUIRED`. |
| `userAgent` | a real desktop Chrome UA | Required and validated as non-blank. jut.su rejects empty / curl-default UAs. |
| `rateLimitRps` | `1.0` | Outbound hard cap. Set lower if you share an account between replicas. |
| `sessionTtl` | 4 hours | Proactive cookie expiry. The decoder also invalidates the session on `JUTSU_PREMIUM_REQUIRED` after a successful login (silent server-side expiry). |
| `loginTimeout` | 15 seconds | Per-login HTTP timeout. |

## Error codes

`JutsuDecodeResult.errorCode()` is one of:

| Code | Meaning | Operator action |
|------|---------|-----------------|
| `JUTSU_FETCH_ERROR` | Network error reaching the episode page (timeout, DNS, TLS). | Check egress connectivity and `jut.su` status. |
| `JUTSU_EMPTY_RESPONSE` | Upstream returned an empty body. | Retry; if persistent, capture upstream traffic. |
| `JUTSU_CLOUDFLARE_BLOCKED` | Cloudflare's `Just a moment…` challenge. | Rotate egress IP or wait for the challenge cookie to expire. |
| `JUTSU_PREMIUM_REQUIRED` | Episode requires a Jutsu+ subscription. | Configure `JUTSU_USERNAME` / `JUTSU_PASSWORD`. |
| `JUTSU_PLAYER_MISSING` | Page came back without the `<video>` block. Almost always bot detection. | Check User-Agent, IP reputation, request cadence. |
| `JUTSU_SOURCE_TAG_MISSING` | Player exists but no `<source src="...mp4">` matched. | Schema drift on jut.su's side — capture the response and update regexes. |

## Wiring into Spring Boot

Treat the SDK as plain Java beans. orinuno-app's `JutsuSdkConfiguration` is the canonical example:

```java
@Configuration
public class JutsuSdkConfiguration {

    @Bean
    JutsuConfig jutsuConfig(MyAppProperties props) { /* ... */ }

    @Bean
    JutsuRateLimiter jutsuRateLimiter(MyAppProperties props, MeterRegistry meters) {
        return new JutsuRateLimiter(props::getRateLimitRps, meters);
    }

    @Bean
    JutsuSessionManager jutsuSessionManager(JutsuConfig cfg, JutsuRateLimiter rl,
                                            WebClient.Builder wcb, MeterRegistry meters) {
        return new JutsuSessionManager(cfg, rl, wcb, meters);
    }

    @Bean
    JutsuDriftDetector jutsuDriftDetector() { return new JutsuDriftDetector(); }

    @Bean
    JutsuClient jutsuClient(JutsuConfig cfg,
                            JutsuRateLimiter rl,
                            JutsuSessionManager sm,
                            JutsuDriftDetector drift,
                            WebClient.Builder wcb) {
        return JutsuClient.builder()
                .config(cfg)
                .rateLimiter(rl)
                .sessionManager(sm)
                .driftDetector(drift)
                .webClientBuilder(wcb)
                .build();
    }
}
```

Notice that the same `JutsuRateLimiter`, `JutsuSessionManager` and `JutsuDriftDetector` beans are reused by `JutsuClient` — building a fresh limiter inside `jutsuClient(...)` would silently double the outbound RPS budget, and a fresh detector would split drift counters across two snapshots.

### Schema drift in production

Every parser is drift-aware (`drift/`). In **lenient** mode (the default) the SDK logs the signal and returns whatever it could parse; in **strict** mode it throws `JutsuDriftException`. Strict mode is used by:

- `JutsuStrictReplayTest` — re-runs every parser against its captured fixture and asserts zero drift events. This is the regression net.
- orinuno-app's `JutsuDriftScheduledProbe` — calls a canary set of endpoints periodically and surfaces health on `GET /api/v1/sources` so downstream rankers can demote jut.su when something changes upstream.

Use `JutsuClient.getDriftSnapshot()` to read the live signal yourself.

## Quirks

The decoder handles three known jut.su weirdnesses; if you hit a new one, please open an issue with the captured response body:

1. **Charset mismatch** — jut.su responds with `windows-1251` even on `application/xhtml+xml`. The decoder honours the `Content-Type` charset and falls back to `windows-1251` so cyrillic premium-overlay text does not mojibake.
2. **Premium gating leaks `<source>` tags** — gated episodes still ship `<source>` elements but their `src` points to `gen.jut.su/templates/school/images/pixel.png`. We detect this BEFORE regex extraction so the result is `JUTSU_PREMIUM_REQUIRED` rather than a misleading `JUTSU_SOURCE_TAG_MISSING`.
3. **Yandex CDN URLs are session-bound** — the URLs returned by the decoder are signed against the IP/cookie session that fetched the episode page. They will return HTTP 403 if you try to open them directly in a different browser. orinuno-app solves this with a backend pass-through proxy (`JutsuStreamProxyController`); standalone SDK users need a similar proxy or have to play the URLs from the same session that decoded them.

## Versioning

This module follows the parent reactor's version (`0.1.0` today). Public API surface:

- Facade: `com.orinuno.jutsu.JutsuClient`, `JutsuConfig`, `JutsuDecodeResult`, `JutsuErrorCodes`.
- Subpackages: `auth.JutsuSessionManager`, `catalog.*`, `decoder.JutsuDecoder`, `drift.*`, `episode.*` (sealed `JutsuPageMeta` + `JutsuEpisodeMeta` / `JutsuFilmMeta`), `filter.*`, `info.*` (incl. `JutsuFilmListing`), `notice.*`, `parser.JutsuSourceParser`, `ratelimit.JutsuRateLimiter`.

Breaking changes here will bump the minor version.

## Reference projects

- [`AnimeParsers`](https://github.com/AmcfaR/AnimeParsers/blob/main/anime_parsers_ru/jutsu_parser_async.py) — Python equivalent, uses similar DLE-cookie + sticky-session approach.
- [GitHub Tier A survey](../docs/research/2026-05-04-jut-su-tier-A-per-repo-analysis.md) — построчный разбор 95 релевантных поиску `jut.su` репозиториев (orinuno research, 2026-05).

## See also

- [ADR 0009](../docs/adr/0009-player4-jutsu-decoder.md) — original PLAYER-4 decision (decoder design)
- [ADR 0012](../docs/adr/0012-jutsu-sdk-extraction.md) — extraction into this module (Step 2 of the API/module split)
- [ADR 0015](../docs/adr/0015-jutsu-full-browser-parity.md) — full browser parity (catalog / search / info / episode meta / notice feed) + drift detection
- [`docs/quirks-and-hacks.md`](../docs/quirks-and-hacks.md) — full DLE auth + Yandex CDN session-binding writeup
- [`docs/runbooks/provider-cdn-block.md`](../docs/runbooks/provider-cdn-block.md) — operator runbook for `JUTSU_*` error codes
