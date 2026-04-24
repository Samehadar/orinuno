---
title: HLS Manifest Retrieval
description: How GET /api/v1/hls/{id}/manifest returns a playable, absolutized m3u8 — fresh decode, proxy-aware fetch, and URL rewriting.
---

The HLS endpoints are the bridge between Orinuno and any downstream player or
downloader that speaks m3u8. They deliberately do not cache the playlist — the
underlying mp4 URL has a short TTL, so every manifest is fetched fresh.

![HLS manifest retrieval](/orinuno/diagrams/7_hls_manifest.svg)

## Request flow

1. `GET /api/v1/hls/{variantId}/manifest` lands in `HlsController`.
2. `HlsManifestService` loads the variant from MySQL and calls
   `KodikVideoDecoderService.decode(...)` — no TTL cache is consulted; the
   caller explicitly wants a fresh URL.
3. The decoder returns `Map<quality, cdnUrl>`. We pick the best quality and
   append `:hls:manifest.m3u8` to get the HLS entry point.
4. `ProxyWebClientService` fetches the raw m3u8 through a proxy (with a
   direct fallback and a `Referer: kodikplayer.com` header).
5. `absolutizeManifest(raw, url)` rewrites every relative URL inside the
   playlist so that the consumer can download segments without knowing the
   manifest's base URL.

## Absolutization rules

The rewriter classifies every line:

| Input | Action |
| --- | --- |
| `#EXTM3U`, `#EXT-X-…`, empty line | Pass through unchanged |
| Absolute URL (`http://`, `https://`, `//`) | Pass through unchanged |
| Root-relative (`/path/...`) | Prefix with `scheme://host` |
| Relative (`segment.ts`, `../`, etc.) | Prefix with `dirname(manifestUrl)` |

The result is a playlist where every URL is fully qualified, suitable for
`ffmpeg`, `mpv`, VLC, or any HLS client without extra `-base_url` flags.

## Lightweight variant

`GET /api/v1/hls/{variantId}/url` returns only the final `.m3u8` URL without
downloading or rewriting the playlist. Use it when the downstream client can
speak directly to the CDN — for example, an external downloader that handles
relative URLs on its own.

## Related

- [Video decoding](/orinuno/architecture/video-decoding/)
- [Video download](/orinuno/architecture/video-download/)
- [API overview](/orinuno/api/overview/)
