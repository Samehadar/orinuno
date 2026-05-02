# ADR 0009 — PLAYER-4 (JutSu): direct-MP4 decoder, premium-tier blocked

- **Status**: Accepted (implementation deferred — blocked on [ADR 0005](0005-multi-provider-source-video-split.md))
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → IDEA-AP-2, [ADR 0005](0005-multi-provider-source-video-split.md), [ADR 0006](0006-sibnet-and-aniboom-providers.md), reference: `AnimeParsers/src/anime_parsers_ru/parser_jutsu.py`

## Context

JutSu (jut.su) is a Russian-language anime hosting platform. Compared to Kodik / Aniboom / Sibnet:

- **Pros**: Direct `.mp4` URLs (no HLS playlist parsing, no ROT cipher, no Base64). Best **subtitled** anime coverage among the four (most other providers default to dub).
- **Cons**: Page protected by Cloudflare in some regions. Some episodes are gated behind a "premium" tier that requires a paid account. Geo-fencing varies per episode (older anime is freely available; new releases are sometimes RU-only).

JutSu's decoder is the simplest of the four — the page HTML embeds the direct video URL in a `<source>` tag inside `<video>`. AnimeParsers' `parser_jutsu.py` is ~80 lines.

## Decision

**Implement JutSu as the third provider (after Kodik + Sibnet) under ADR 0005's `episode_source` + `episode_video` schema. Treat premium-tier episodes as "decode-failed-permanent" — do not retry, do not block on user-supplied premium credentials.**

### Decoder strategy

1. `GET <jutsu-episode-url>` with stable User-Agent. JutSu does NOT require a Referer for the page fetch.
2. Parse the response HTML for `<source src="..." type="video/mp4">`. There is typically ONE source per episode (sometimes a 720p + 1080p pair).
3. **Premium check**: if the page contains a "Только для премиум-подписчиков" marker (or HTTP 200 with no `<source>` tag and a `class="premium-gate"` div), mark the decode as `decode_last_error='JUTSU_PREMIUM_REQUIRED'` and DO NOT retry. Premium gating is permanent without user credentials.
4. **Cloudflare check**: if the page returns HTTP 403 with a Cloudflare challenge body, mark `decode_last_error='JUTSU_CLOUDFLARE_BLOCKED'` and **do** retry — Cloudflare challenges are usually transient and the next request from a different egress IP will pass.
5. The `<source src>` URL is a direct `.mp4`. Persist as `episode_video(quality='720'|'1080', video_format='video/mp4', video_url=<src>, ttl_seconds=NULL)`. JutSu URLs do not expire — TTL is null.

### Where in code (after PLAYER-1 ships)

- `service/provider/jutsu/JutsuSourceParser.java` — accepts a JutSu URL, returns an `episode_source` row.
- `service/provider/jutsu/JutsuDecoderService.java` — reads `episode_source`, persists `episode_video`.
- `service/provider/jutsu/JutsuDecoderRegexTest.java` — fixtures of the page HTML to lock the `<source>` regex; separate fixtures for the premium-gate and Cloudflare-challenge paths.

### Streaming proxy considerations

JutSu URLs do NOT require Referer header injection. They DO send `Accept-Ranges: bytes` so range requests work natively — the streaming proxy can pass through ranges without re-fetching the entire `.mp4`.

## Consequences

### Positive

- Simplest decoder of the four — short JIRA-to-ship time once ADR 0005 lands.
- Direct `.mp4` is friendliest to consumers that don't want to parse HLS.
- Best subtitled-anime coverage; closes a gap that none of the other three providers cover well.

### Negative

- Premium-gated episodes are a permanent dead-end. Operators will see decode-failure rates that look alarming until they realize it's the premium tier, not a decoder break. **Documentation must call this out.**
- Cloudflare challenges add a non-zero baseline failure rate even from CIS egress.

### Neutral

- JutSu's catalogue is anime-only. No need to broaden the provider model to accept a `category` filter.

## Blocked on

- **ADR 0005 Phases A–C** — schema must exist.
- **CIS-region egress** — same as Kodik / Sibnet.

## Implementation tracker

| Step | Status | Notes |
|---|---|---|
| ADR 0005 Phases A–D shipped | Pending | blocking |
| `JutsuSourceParser` + `JutsuDecoderService` | Pending | |
| Premium-gate + Cloudflare-challenge regression tests | Pending | |
| Streaming proxy validation (range requests) | Pending | |
