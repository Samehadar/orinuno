# jutsu-sdk

Standalone reactive Java client for [jut.su](https://jut.su) episode pages.

This module is the **second per-source SDK** extracted out of the [orinuno](../README.md) project (after [`kodik-sdk-drift`](../kodik-sdk-drift)). It owns three things end users care about:

- **DLE authentication** with sticky cookies (`JutsuSessionManager`) — fetches the homepage POST shape, parses the four DLE-flavoured cookies, refreshes them on TTL expiry or explicit invalidation.
- **1 RPS hard cap** outbound to `jut.su` (`JutsuRateLimiter`) — single Bucket4j bucket, hot-swappable RPS via a `DoubleSupplier`, reactive `Mono.delay`-based suspension (no thread blocking).
- **Episode-page decoder** (`JutsuDecoder`) — fetches the episode page with the cached cookies, recognises the `tab_need_plus` overlay + `pixel.png` placeholder URLs (premium gate), classifies Cloudflare challenges and bot-detection responses with their own error codes, and returns one mp4 URL per quality bucket.

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
    JutsuClient jutsuClient(JutsuConfig cfg, JutsuRateLimiter rl,
                            JutsuSessionManager sm, WebClient.Builder wcb) {
        return new JutsuClient(cfg, rl, sm, wcb);
    }
}
```

Notice that the same `JutsuRateLimiter` and `JutsuSessionManager` beans are reused by `JutsuClient` — building a fresh limiter inside `jutsuClient(...)` would silently double the outbound RPS budget.

## Quirks

The decoder handles three known jut.su weirdnesses; if you hit a new one, please open an issue with the captured response body:

1. **Charset mismatch** — jut.su responds with `windows-1251` even on `application/xhtml+xml`. The decoder honours the `Content-Type` charset and falls back to `windows-1251` so cyrillic premium-overlay text does not mojibake.
2. **Premium gating leaks `<source>` tags** — gated episodes still ship `<source>` elements but their `src` points to `gen.jut.su/templates/school/images/pixel.png`. We detect this BEFORE regex extraction so the result is `JUTSU_PREMIUM_REQUIRED` rather than a misleading `JUTSU_SOURCE_TAG_MISSING`.
3. **Yandex CDN URLs are session-bound** — the URLs returned by the decoder are signed against the IP/cookie session that fetched the episode page. They will return HTTP 403 if you try to open them directly in a different browser. orinuno-app solves this with a backend pass-through proxy (`JutsuStreamProxyController`); standalone SDK users need a similar proxy or have to play the URLs from the same session that decoded them.

## Versioning

This module follows the parent reactor's version (`0.1.0` today). Public API surface is `com.orinuno.jutsu.JutsuClient`, `JutsuConfig`, `JutsuDecodeResult`, `JutsuErrorCodes`, plus the package-level entry points (`auth.JutsuSessionManager`, `decoder.JutsuDecoder`, `parser.JutsuSourceParser`, `ratelimit.JutsuRateLimiter`). Breaking changes here will bump the minor version.

## Reference projects

- [`AnimeParsers`](https://github.com/AmcfaR/AnimeParsers/blob/main/anime_parsers_ru/jutsu_parser_async.py) — Python equivalent, uses similar DLE-cookie + sticky-session approach.

## See also

- [ADR 0009](../docs/adr/0009-player4-jutsu-decoder.md) — original PLAYER-4 decision (decoder design)
- [ADR 0012](../docs/adr/0012-jutsu-sdk-extraction.md) — extraction into this module (Step 2 of the API/module split)
- [`docs/quirks-and-hacks.md`](../docs/quirks-and-hacks.md) — full DLE auth + Yandex CDN session-binding writeup
- [`docs/runbooks/provider-cdn-block.md`](../docs/runbooks/provider-cdn-block.md) — operator runbook for `JUTSU_*` error codes
