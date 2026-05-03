# aniboom-sdk

Standalone reactive Java client for [aniboom.one](https://aniboom.one) `/embed/` pages.

This module is the **fourth per-source SDK** extracted out of the [orinuno](../README.md) project (after [`kodik-sdk-drift`](../kodik-sdk-drift), [`jutsu-sdk`](../jutsu-sdk) and [`sibnet-sdk`](../sibnet-sdk)). Aniboom is stateless (no auth, no rate limit, no session) but its embed page carries an HTML-entity-encoded JSON blob with HLS/DASH manifests, so the SDK pulls in `jackson-databind` on top of the per-source-SDK baseline.

It is intentionally Spring-Boot-free: the only Spring dependency is `spring-webflux` (for `WebClient`) and `spring-context` (transitively required by WebClient on Spring 6). No `@Component`, no auto-configuration, no `@ConfigurationProperties` — consumers wire the SDK manually.

## Quick start

```xml
<dependency>
    <groupId>com.orinuno</groupId>
    <artifactId>aniboom-sdk</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
AniboomClient client = AniboomClient.builder().build();

AniboomDecodeResult result = client.decode("https://aniboom.one/embed/abc123").block();
if (result.success()) {
    String hls = result.qualities().get("auto");   // HLS master playlist
    String dash = result.qualities().get("dash");  // optional DASH manifest
    System.out.println("HLS: " + hls);
} else {
    System.err.println("error: " + result.errorCode());
}
```

You can also extract the embed id from a URL first:

```java
String id = AniboomSourceParser.extractEmbedId(
        "https://aniboom.one/embed/abc123?autoplay=1").orElseThrow();
client.decode(AniboomSourceParser.toEmbedUrl(id)).subscribe(...);
```

## Configuration

All configuration is done via the `AniboomConfig` builder. Defaults match the production-tested values used by `orinuno-app`:

| Field | Default | Purpose |
|-------|---------|---------|
| `baseUrl` | `https://aniboom.one` | Base host for the embed pages. |
| `referer` | `https://animego.org/` | Required header — Aniboom's anti-hotlink check accepts the `animego.org` first-party Referer (it is the player's primary publisher) but rejects empty Referer with HTTP 403. |
| `userAgent` | a real desktop Chrome UA | Required and validated as non-blank. |

## Error codes

`AniboomDecodeResult.errorCode()` is one of:

| Code | Meaning | Operator action |
|------|---------|-----------------|
| `ANIBOOM_FETCH_ERROR` | Network error reaching the embed page (timeout, DNS, TLS, 5xx). | Check egress connectivity and `aniboom.one` status. |
| `ANIBOOM_DATA_INPUT_MISSING` | Page came back but `<input id="video-data" data-parameters="…">` is gone. | Schema drift on Aniboom's side — capture the body. |
| `ANIBOOM_GEO_BLOCKED` | The `data-parameters` blob is `{}`. Almost always means the request landed from a non-CIS exit. | Switch egress to a CIS country (KZ/RU/BY/KG). |
| `ANIBOOM_JSON_PARSE_ERROR` | The blob exists but contains malformed JSON. | Capture the body — likely Aniboom changed the entity-encoding scheme. |
| `ANIBOOM_NO_PLAYLIST` | JSON parsed but neither `hls` nor `dash` was present. | Title may not have a manifest yet; retry later. |

## Result shape

| Quality key | Meaning |
|-------------|---------|
| `auto` | HLS master playlist (`application/x-mpegURL`). Use a player that follows EXT-X-STREAM-INF for adaptive bitrate selection. |
| `dash` | DASH manifest (`application/dash+xml`). Optional — Aniboom does not always publish both. |

When both are present, `format()` returns `application/x-mpegURL` (HLS is the primary track and most players prefer it).

## Wiring into Spring Boot

Treat the SDK as plain Java beans. orinuno-app's `AniboomSdkConfiguration` is the canonical example:

```java
@Configuration
public class AniboomSdkConfiguration {

    @Bean
    AniboomConfig aniboomConfig(MyAppProperties props) {
        return AniboomConfig.builder()
                .userAgent(props.userAgent())
                .build();
    }

    @Bean
    AniboomClient aniboomClient(AniboomConfig cfg, WebClient.Builder wcb) {
        return new AniboomClient(cfg, wcb);
    }
}
```

## Versioning

This module follows the parent reactor's version (`0.1.0` today). Public API surface is `com.orinuno.aniboom.AniboomClient`, `AniboomConfig`, `AniboomDecodeResult`, `AniboomErrorCodes`, plus the package-level entry points (`decoder.AniboomDecoder`, `parser.AniboomSourceParser`). Breaking changes here will bump the minor version.

## See also

- [ADR 0006](../docs/adr/0006-sibnet-and-aniboom-providers.md) — original PLAYER-2 decision (Aniboom integration approach)
- [ADR 0013](../docs/adr/0013-sibnet-and-aniboom-sdk-extraction.md) — extraction into this module (Step 3 of the API/module split)
- [`docs/runbooks/provider-cdn-block.md`](../docs/runbooks/provider-cdn-block.md) — operator runbook for `ANIBOOM_*` error codes
