# ADR 0006 — Sibnet (PLAYER-3) and Aniboom (PLAYER-2) integration approaches

- **Status**: Accepted (implementation deferred — blocks on PLAYER-1 / [ADR 0005](0005-multi-provider-source-video-split.md))
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → PLAYER-2, PLAYER-3, PLAYER-4, [ADR 0005](0005-multi-provider-source-video-split.md), reference projects `../AnimeParsers/` (Python), `../kodikwrapper/` (TS)

## Context

The plan calls for Sibnet (PLAYER-3) and Aniboom (PLAYER-2) as alternative video providers. Both are mainstream Russian-language anime/film hosting platforms used as Kodik fallbacks across the reference projects (AnimeParsers ships full Python decoders for both; kodikwrapper documents but does not ship Aniboom). PLAYER-3 specifically asked to "inline the Aniboom note in Javadoc" — i.e. ship the Sibnet decoder now, and leave a clear stub for Aniboom so a future contributor knows where to plug it in.

Both decoders are blocked on PLAYER-1 (ADR 0005) because they need a place to persist provider-specific source URLs that isn't `kodik_episode_variant`. ADR 0005 sets up `episode_source` + `episode_video` for exactly this use case. This ADR pins the **decoder approach** for each provider so the implementation work can land cleanly when PLAYER-1 ships.

## Decision

Implement two new provider-specific decoder services, both feeding ADR 0005's `episode_source` + `episode_video`:

### Sibnet (PLAYER-3)

**Provider URL shape**: `https://video.sibnet.ru/shell.php?videoid=<id>` (iframe URL embedded in the HTML page from `https://video.sibnet.ru/video<id>-<slug>.html`).

**Decoder strategy** (mirrors `AnimeParsers/anime_parsers/parsers/sibnet.py`):

1. `GET https://video.sibnet.ru/shell.php?videoid=<id>` with **stable Referer header** `https://video.sibnet.ru/` and User-Agent from `RotatingUserAgentProvider.stableDesktop()`. Sibnet's anti-hotlink check is "Referer must be sibnet.ru".
2. Regex-extract the playlist URL: `player\.src\(\[\{src:\s*"([^"]+)"`.
3. Resolve the playlist URL relative to `https://video.sibnet.ru` (Sibnet emits a path-relative URL like `/v/...`).
4. The resolved URL is a **direct `.mp4`** (not HLS). Persist as `episode_video(quality='720', video_format='video/mp4', video_url=<resolved>)`.
5. **No expiry** on Sibnet URLs — the URL itself is stable. But the anti-hotlink Referer is enforced on every fetch, so the streaming proxy must inject the Referer header. Document this constraint in the streaming controller.

**Failure modes** (catch + classify):

- HTTP 404 on shell.php → video deleted. Mark `episode_video.decode_last_error='SIBNET_VIDEO_NOT_FOUND'`. Don't retry.
- HTTP 200 but regex doesn't match → Sibnet shipped a player rewrite. Mark `episode_video.decode_last_error='SIBNET_PLAYER_REGEX_BREAK'`. Counts as a regex break — alert the operator (same Prometheus counter as the Kodik regex break).
- Direct `.mp4` URL returns HTTP 403 on subsequent stream → anti-hotlink failure. Don't mark the URL as broken; the streaming proxy needs a Referer fix.

**Where in code (after PLAYER-1 ships)**:

- `service/provider/sibnet/SibnetSourceParser.java` — accepts a Sibnet URL or `videoid`, returns an `episode_source` row.
- `service/provider/sibnet/SibnetDecoderService.java` — accepts a `episode_source` row, persists `episode_video`.
- `service/provider/sibnet/SibnetDecoderRegexTest.java` — fixtures of `shell.php` HTML to lock the regex.

**Skip reasons / parked decisions**:

- We do NOT bring in Sibnet's optional subtitle URLs in this iteration. Subtitles are tracked separately in PLAYER-3.subs (BACKLOG).
- We do NOT auto-rewrite Sibnet `.mp4` URLs to `.m3u8` even when Sibnet exposes both — direct `.mp4` is faster for our streaming use case.

### Aniboom (PLAYER-2)

**Provider URL shape**: `https://aniboom.one/embed/<id>?episode=<n>&translation=<id>` (iframe). Master URL on the same domain.

**Decoder strategy** (mirrors `AnimeParsers/anime_parsers/parsers/aniboom.py`):

1. `GET <iframe-url>` with stable User-Agent and `Referer: https://animego.org/` (Aniboom's anti-hotlink check accepts `animego.org` as a referrer).
2. Extract the **encrypted JSON blob** from `<input id="video-data" data-parameters="<json-string>">`. The blob is HTML-entity-decoded JSON containing `hls`, `dash`, and `subtitle_url` keys.
3. Decode the HLS / DASH URL (no extra cipher — Aniboom does NOT use ROT or Base64 like Kodik).
4. Aniboom serves both **HLS** (`m3u8`) and **MPEG-DASH** (`mpd`). For our use case, prefer DASH only when HLS is missing — most browsers and our existing pipeline understand HLS, and we already have the master-playlist resolver from DOWNLOAD-PARALLEL.
5. Persist as `episode_video(quality='auto', video_format='application/x-mpegURL', video_url=<hls-or-dash>)`. Note: Aniboom's HLS URL **is** a master playlist (it contains `#EXT-X-STREAM-INF`); the DOWNLOAD-PARALLEL resolver handles it transparently.

**Failure modes**:

- HTTP 200 but no `<input id="video-data">` → Aniboom shipped a player rewrite. Mark `episode_video.decode_last_error='ANIBOOM_DATA_INPUT_MISSING'`.
- HTTP 200 with `data-parameters` empty → Aniboom geo-blocked the request (typical for non-RU IPs). Mark `episode_video.decode_last_error='ANIBOOM_GEO_BLOCKED'`. Alert if the rate exceeds 5% of attempts.
- TTL on Aniboom URLs is **shorter** than Kodik (typically ~6h). Set `episode_video.ttl_seconds=21600` so the existing TTL refresh scheduler picks it up early.

**Where in code (after PLAYER-1 ships)**:

- `service/provider/aniboom/AniboomSourceParser.java`
- `service/provider/aniboom/AniboomDecoderService.java`
- `service/provider/aniboom/AniboomDecoderRegexTest.java` — fixtures of the `<input id="video-data">` HTML.

**Inline-Javadoc note for PLAYER-2 (PLAYER-3 plan callout)**: Until the Aniboom decoder ships, every public Javadoc in the new `service/provider/` package should carry a `// PLAYER-2: Aniboom integration parked behind PLAYER-1 (ADR 0005); see ADR 0006.` marker so contributors who land in the package see the link to this ADR immediately.

## Consequences

### Positive

- Both providers fit the ADR 0005 schema cleanly — no schema changes required to add either.
- Provider-specific decoders are isolated under `service/provider/<name>/`, mirroring the AnimeParsers Python package layout (familiar to anyone who has read that codebase).
- The Sibnet "direct .mp4 with Referer header" pattern is documented up-front so the streaming proxy gets the right header injection on first ship.

### Negative

- We're locking in two decoder implementations that both depend on regex-based extraction. Same Kodik-style fragility (provider ships a rewrite → decoder breaks). Mitigated by the same Prometheus counter / quirks-doc pattern we use for Kodik.
- Geo-fencing is more aggressive on Aniboom (RU IPs strongly preferred) than Kodik (CIS region). Operators that don't have a CIS-region egress will see ~100% Aniboom failure. Document this in the runbook before shipping PLAYER-2.

### Neutral

- We ship Sibnet first (PLAYER-3) because (a) it's simpler (no DASH, no encrypted blob) and (b) a working Sibnet integration validates the ADR 0005 schema in production before we invest in the more complex Aniboom path.

## Implementation tracker

| Step | Status | PR | Notes |
|---|---|---|---|
| ADR 0005 implementation (Phases A–C) | Pending | — | blocking |
| Sibnet decoder (PLAYER-3) | Pending | — | depends on ADR 0005 Phase D |
| Aniboom decoder (PLAYER-2) | Pending | — | depends on ADR 0005 Phase D2 + RU egress confirmed |
