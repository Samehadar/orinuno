---
title: Video Download
description: Five-phase download pipeline — Playwright captures the HLS manifest, java.net.http.HttpClient pulls segments in parallel, ffmpeg remuxes to MP4.
---

Direct HTTP clients cannot download video from `solodcdn.com` — the CDN
returns `HTTP 200` with `Content-Length: 0` to anything that does not look
like a real browser. Orinuno works around this with a five-phase pipeline
built on Playwright and `java.net.http.HttpClient`.

![Video download via Playwright + HLS](/orinuno/diagrams/6_video_download_playwright.svg)

## Phases

1. **Navigate** — headless Chromium loads `https://kodikplayer.com/seria/{id}/{hash}/720p`. The page includes the player JS, analytics, and ad scripts.
2. **Trigger playback** — we simulate a click on `.play_button` and in the centre of the viewport. The player POSTs to `/ftor`, receives the encoded URLs, processes VAST ads, and starts the video load.
3. **Intercept the video URL** — `page.onResponse()` captures the request to `solodcdn.com/s/m/...`. We do not call `response.body()` on it — the file is too large for a single `.body()` call.
4. **Download via `APIRequestContext`** — `context.request().get(videoUrl)`
   is a server-side Playwright call. It bypasses CORS (unlike `fetch()` from
   `page.evaluate(...)`) and inherits cookies from the `BrowserContext`.
   The CDN sees valid cookies and returns real bytes.
5. **Handle HLS in parallel + remux** — if the body starts with `#EXTM3U`,
   we parse the list of `.ts` segments (often 200–1300 of them), extract
   cookies from the `BrowserContext`, and pull segments in parallel via
   `java.net.http.HttpClient` (8–16 threads, configurable via
   `hls-concurrency`). We do not reuse `APIRequestContext` here — it shares
   a single WebSocket and is not thread-safe. Segments are concatenated in
   order into a `.ts` file, then remuxed to `.mp4` via
   `ffmpeg -c copy -movflags +faststart`.

## Why not a plain HTTP client?

| Approach | Result | Reason |
| --- | --- | --- |
| `WebClient` + `Referer`/`User-Agent` | 0 bytes | CDN checks full browser context, not just headers |
| `WebClient` + manual redirect handling | 0 bytes | 302 passes, final response is empty |
| Single-pass `exchangeToFlux` | 0 bytes | CDN blocks at the TLS / fingerprint layer |
| Playwright + `response.body()` | Timeout | `body()` waits for the full stream; video is too large |
| `page.evaluate(fetch(...))` | CORS error | Browser `fetch` blocks cross-origin CDN calls |
| **Playwright `APIRequestContext`** | **Works** | Server-side call with cookies from `BrowserContext` |
| `APIRequestContext` multi-threaded | Errors | Not thread-safe — single WebSocket |
| **Playwright cookies + Java `HttpClient`** | **Works, fast** | Cookies from `BrowserContext`, native parallelism |
| `.ts` → `.mp4` via ffmpeg stream copy | Instant | Browsers cannot play MPEG-TS natively |

## Progress tracking

`VideoDownloadService` keeps an in-memory `DownloadProgress` record with
atomic counters (`totalSegments`, `downloadedSegments`, `totalBytes`). The
REST flow:

- `POST /api/v1/download/{variantId}` returns `IN_PROGRESS` immediately.
- `GET /api/v1/download/{variantId}/status` polls the counters.

## Streaming

`GET /api/v1/stream/{variantId}` serves the local file with full `Range`
support. If the file is missing, the stream endpoint kicks off a fresh
Playwright download before returning bytes. Useful for ad-hoc playback
without having to pre-download.

## Fallback

If Playwright is disabled (`orinuno.playwright.enabled=false`) or fails at
launch, the service falls back to `KodikVideoDecoderService` + `WebClient`.
In practice this fallback returns zero bytes — the CDN blocks it — so only
rely on it as a last resort.

## Configuration

All Playwright-related properties live under `orinuno.playwright.*`. See
[Configuration](/orinuno/getting-started/configuration/).

## Related

- [Video decoding](/orinuno/architecture/video-decoding/)
- [HLS manifest](/orinuno/architecture/hls-manifest/)
- [Operations → Monitoring](/orinuno/operations/monitoring/)
