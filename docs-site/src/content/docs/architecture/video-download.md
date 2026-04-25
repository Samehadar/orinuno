---
title: Video Download
description: Download pipeline with a cached-link fast-path, stealth-hardened Playwright HLS path, and a WebClient direct-MP4 fallback with byte-level progress.
---

Orinuno resolves Kodik video content into a local file through three paths,
picked in priority order inside `VideoDownloadService.downloadWithStrategy`:

1. **Fast-path** — direct CDN pull of an already-decoded `mp4_link`.
2. **Playwright HLS** — headless Chromium replays the player, captures the
   `.m3u8`, and pulls segments in parallel.
3. **WebClient direct MP4** — reactive HTTP client, used when Playwright is
   unavailable or fails.

![Video download via Playwright + HLS](/orinuno/diagrams/6_video_download_playwright.svg)

## Path 1 — Cached mp4_link fast-path

If `kodik_episode_variant.mp4_link` is already populated and starts with
`http`, we skip Playwright entirely and go straight to the CDN:

```
variant.mp4_link (cached) → fetchWithRedirects → bodyToFlux(DataBuffer)
                          → DownloadProgress.addBytes(...)
                          → disk
```

Typical latency to first byte: **~2 seconds**. Byte-level progress is
reported from the very first chunk (see `expectedTotalBytes` below).

If the cached URL has expired (CDN 403/404 or truncated body), the service
transparently falls back to Path 3 — a fresh decode plus WebClient download.

## Path 2 — Playwright + HLS

Used when `mp4_link` is not yet decoded. Playwright replays the player,
captures the `.m3u8` manifest, and Java `HttpClient` fetches segments in
parallel — this path is **~9× faster** than direct MP4 download on typical
Kodik content (parallelism 16 vs. a single TCP stream).

Pipeline:

1. **Navigate** — headless Chromium loads the player URL
   (`kodikplayer.com/seria/{id}/{hash}/720p`) inside a `BrowserContext`
   created by `newStealthContext()`.
2. **Trigger playback** — simulate a click on the play button and the centre
   of the viewport. The player POSTs to `/ftor`, processes VAST ads, and
   starts loading the video.
3. **Intercept the manifest** — `page.onResponse()` captures the request to
   `solodcdn.com/s/m/...`. We do not call `response.body()` on it — the file
   is too large for a single `.body()` call.
4. **Download via `APIRequestContext`** — `context.request().get(videoUrl)`
   is a server-side Playwright call. It bypasses CORS (unlike `fetch()` from
   `page.evaluate(...)`) and inherits cookies from the `BrowserContext`. The
   CDN sees valid cookies and returns real bytes.
5. **HLS in parallel + remux** — if the body starts with `#EXTM3U`, parse
   the list of `.ts` segments (often 200–1300 of them), extract cookies from
   the `BrowserContext`, and pull segments in parallel via
   `java.net.http.HttpClient` (8–16 threads, configurable via
   `hls-concurrency`). We do not reuse `APIRequestContext` here — it shares
   a single WebSocket and is not thread-safe. Segments are concatenated in
   order into a `.ts` file, then remuxed to `.mp4` via
   `ffmpeg -c copy -movflags +faststart`.

### Stealth shim

`PlaywrightVideoFetcher.newStealthContext()` patches the most common
headless-detection signals via `context.addInitScript(...)` **before any
navigation**, so the shim also applies to nested iframes:

- `navigator.webdriver` → `undefined`
- `navigator.languages` → `['en-US', 'en']`
- `navigator.plugins` → non-empty mock array
- `window.chrome` → object with a `runtime` stub
- `Notification.permission` returned through the Permissions API
- Context defaults: `Chrome/135` UA, `1280×720` viewport, `en-US` locale,
  `Europe/London` timezone

This does **not** solve IP-based geo-blocking — Kodik's player refuses to
start from blocked regions regardless of browser fingerprint. For that,
rotate egress through `kodik_proxy` (see the *Geo-block handling* section
below).

## Path 3 — WebClient direct MP4 (fallback)

Used when Playwright is disabled (`orinuno.playwright.enabled=false`) or
times out. Pipeline:

1. `KodikVideoDecoderService.decode(kodik_link)` resolves fresh quality URLs
   via the public `/ftor` endpoint.
2. `pickBestQualityUrl(...)` picks the highest numeric quality that is an
   `http` URL — defensive filters drop `_geo_blocked` sentinels and any
   value not starting with `http`.
3. `fetchWithRedirects(...)` follows up to 5 redirects through the reactive
   `kodikCdnWebClient`. On the terminal 2xx response, the `Content-Length`
   header populates `expectedTotalBytes`.
4. `bodyToFlux(DataBuffer)` streams the payload; each `DataBuffer` updates
   `totalBytes` via `progress.addBytes(buf.readableByteCount())` and is then
   written to disk.

This path works on CDNs that accept plain HTTP clients with a realistic
`User-Agent` and follows Kodik's redirect chain. It is slower than Path 2
because it is a single TCP stream, not a segment-parallel HLS pull.

## Why not a plain HTTP client? (historical)

| Approach | Result | Reason |
| --- | --- | --- |
| Single-pass `exchangeToFlux` without redirects | 0 bytes | Kodik CDN responds with a 302 that must be followed manually |
| `WebClient` + manual redirect handling | Works | Current Path 3 |
| Playwright + `response.body()` | Timeout | `body()` waits for the full stream; video is too large |
| `page.evaluate(fetch(...))` | CORS error | Browser `fetch` blocks cross-origin CDN calls |
| **Playwright `APIRequestContext`** | **Works** | Server-side call with cookies from `BrowserContext` |
| `APIRequestContext` multi-threaded | Errors | Not thread-safe — single WebSocket |
| **Playwright cookies + Java `HttpClient`** | **Works, fast** | Cookies from `BrowserContext`, native parallelism |
| `.ts` → `.mp4` via ffmpeg stream copy | Instant | Browsers cannot play MPEG-TS natively |

## Progress tracking

`VideoDownloadService.DownloadProgress` keeps an in-memory record with
atomic counters:

| Field | Populated by | Meaning |
| --- | --- | --- |
| `totalSegments` | Playwright HLS path | Total `.ts` segments in the manifest |
| `downloadedSegments` | Playwright HLS path | Segments completed so far |
| `totalBytes` | Both Playwright and WebClient paths | Bytes written so far |
| `expectedTotalBytes` | WebClient path | `Content-Length` of the final 2xx response |

The REST surface:

- `POST /api/v1/download/{variantId}` — fire-and-forget, returns
  `IN_PROGRESS` immediately.
- `GET /api/v1/download/{variantId}/status` — polls the counters.

The demo UI picks one of three progress modes depending on which counters
are populated:

- **Segments** — shows `XX% · M/N segments · Y MB` (HLS path).
- **Bytes** — shows `XX% · Y MB / Z MB` when `expectedTotalBytes` is known
  (WebClient path with `Content-Length`).
- **Indeterminate** — shows `Initializing…` with an animating pulse bar and
  a `phaseHint` explaining what is happening (`Browser handshake`,
  `Playwright timed out — falling back to direct MP4`, or
  `Decoding fresh CDN URL (fallback)`).

In every mode an `elapsed` timer (e.g. `12s` or `2m 07s`) is shown next to
the caption so it is always obvious that the download is making progress.

## Streaming

`GET /api/v1/stream/{variantId}` serves the local file with full `Range`
support. If the file is missing, the stream endpoint kicks off a fresh
Playwright download before returning bytes. Useful for ad-hoc playback
without having to pre-download.

## Geo-block handling

Kodik IP-blocks the player in some regions (Kazakhstan is the observed
example). Symptoms:

- `decode()` still returns valid CDN URLs (the decode API lives on a
  separate IP policy).
- Playwright loads the player page but the video request never fires → the
  call times out after `videoWaitMs` (30s by default).
- `mp4_link` saved from `/search` is literally the string `"true"` — the
  `_geo_blocked` sentinel. Orinuno defensively filters these out in three
  places (`KodikVideoDecoderService.parseVideoResponse`,
  `ParserService.selectBestQuality`, `VideoDownloadService.pickBestQualityUrl`,
  and `StreamController.pickBestQuality`). A Liquibase migration
  (`20260425010000_cleanup_invalid_mp4_link.sql`) nulls out pre-existing
  bad values on first boot.

Mitigations available today:

- Run the service from an unaffected region.
- Keep the current strategy order — the fast-path works the moment a
  decode has succeeded under a compatible egress.

Planned mitigation (tracked in `BACKLOG.md` as
`IDEA-DOWNLOAD-PROXY`): route each `BrowserContext` through a rotated
`kodik_proxy` entry (`new Browser.NewContextOptions().setProxy(...)`). The
proxy pool and `ProxyProviderService` already exist — `PlaywrightVideoFetcher`
just needs to consume them.

## Fallback

If Playwright is disabled (`orinuno.playwright.enabled=false`) or fails at
launch, Path 3 (WebClient) runs directly. Byte-level progress still
populates via `expectedTotalBytes` / `totalBytes`.

## Configuration

All Playwright-related properties live under `orinuno.playwright.*`. See
[Configuration](/orinuno/getting-started/configuration/).

## Related

- [Video decoding](/orinuno/architecture/video-decoding/)
- [HLS manifest](/orinuno/architecture/hls-manifest/)
- [Operations → Monitoring](/orinuno/operations/monitoring/)
