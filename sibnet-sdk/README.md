# sibnet-sdk

Standalone reactive Java client for [video.sibnet.ru](https://video.sibnet.ru) `shell.php` iframe pages.

This module is the **third per-source SDK** extracted out of the [orinuno](../README.md) project (after [`kodik-sdk-drift`](../kodik-sdk-drift) and [`jutsu-sdk`](../jutsu-sdk)). It is intentionally tiny — Sibnet has no auth, no rate limit and no session — so the whole module is `4 source classes + 4 test classes` and validates that the per-provider SDK template scales down to "regex extractor + WebClient" without ceremony.

It is intentionally Spring-Boot-free: the only Spring dependency is `spring-webflux` (for `WebClient`) and `spring-context` (transitively required by WebClient on Spring 6). No `@Component`, no auto-configuration, no `@ConfigurationProperties` — consumers wire the SDK manually.

## Quick start

```xml
<dependency>
    <groupId>com.orinuno</groupId>
    <artifactId>sibnet-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
SibnetClient client = SibnetClient.builder().build();

SibnetDecodeResult result = client.decode(123456789L).block();
if (result.success()) {
    String url = result.qualities().get("720");
    System.out.println("mp4: " + url);
} else {
    System.err.println("error: " + result.errorCode());
}
```

You can also pass a full iframe URL, or extract the numeric video id from a page URL first:

```java
long id = SibnetSourceParser.extractVideoId(
        "https://video.sibnet.ru/video888-naruto-episode-1.html").orElseThrow();
client.decode(id).subscribe(...);
```

## Configuration

All configuration is done via the `SibnetConfig` builder. Defaults match the production-tested values used by `orinuno-app`:

| Field | Default | Purpose |
|-------|---------|---------|
| `baseUrl` | `https://video.sibnet.ru` | Used for absolutising relative `src` fragments returned in the iframe HTML. |
| `referer` | `https://video.sibnet.ru/` | Required header — Sibnet's anti-hotlink check rejects empty / foreign Referer with HTTP 403. |
| `userAgent` | a real desktop Chrome UA | Required and validated as non-blank. |

## Error codes

`SibnetDecodeResult.errorCode()` is one of:

| Code | Meaning | Operator action |
|------|---------|-----------------|
| `SIBNET_FETCH_ERROR` | Network error reaching the iframe (timeout, DNS, TLS, 5xx). | Check egress connectivity and `video.sibnet.ru` status. |
| `SIBNET_PLAYER_REGEX_BREAK` | Page came back but the `player.src([{src:"…"}])` call was missing. Either an empty body or schema drift on Sibnet's side. | Capture the response body and update `SibnetDecoder.PLAYER_SRC`. |
| `SIBNET_INVALID_SRC` | The regex matched but the captured fragment was unparseable. | As above — capture the body and post the substring. |
| `SIBNET_VIDEO_NOT_FOUND` | Sibnet returned 404. The video has been deleted by the uploader. | **Permanent — do not retry.** Mark the source dead in your catalog. |

## Streaming the decoded URL

Sibnet does not sign or session-bind its `.mp4` URLs — they are stable across requests. **However**, if you stream them through your own backend proxy, the proxy MUST inject `Referer: https://video.sibnet.ru/` upstream or Sibnet's CDN will return 403. The same Referer is required for the iframe page fetch and the resolved `.mp4` GET.

## Wiring into Spring Boot

Treat the SDK as plain Java beans. orinuno-app's `SibnetSdkConfiguration` is the canonical example:

```java
@Configuration
public class SibnetSdkConfiguration {

    @Bean
    SibnetConfig sibnetConfig(MyAppProperties props) {
        return SibnetConfig.builder()
                .userAgent(props.userAgent())
                .build();
    }

    @Bean
    SibnetClient sibnetClient(SibnetConfig cfg, WebClient.Builder wcb) {
        return new SibnetClient(cfg, wcb);
    }
}
```

## Versioning

This module follows the parent reactor's version (`0.1.0` today). Public API surface is `com.orinuno.sibnet.SibnetClient`, `SibnetConfig`, `SibnetDecodeResult`, `SibnetErrorCodes`, plus the package-level entry points (`decoder.SibnetDecoder`, `parser.SibnetSourceParser`). Breaking changes here will bump the minor version.

## See also

- [ADR 0006](../docs/adr/0006-sibnet-and-aniboom-providers.md) — original PLAYER-3 decision (Sibnet integration approach)
- [ADR 0013](../docs/adr/0013-sibnet-and-aniboom-sdk-extraction.md) — extraction into this module (Step 3 of the API/module split)
- [`docs/runbooks/provider-cdn-block.md`](../docs/runbooks/provider-cdn-block.md) — operator runbook for `SIBNET_*` error codes
