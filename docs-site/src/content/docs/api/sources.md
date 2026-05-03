---
title: Sources & Multi-Provider API
description: Per-source endpoints (kodik, jutsu, sibnet, aniboom), the resource-style multi-source ranker, and the deprecation aliases that keep older clients working.
---

The "sources" tier is the open-source-friendly entry point. It gives you a
uniform per-provider surface (`/api/v1/sources/{provider}/...`) plus a
resource-style ranker (`/api/v1/anime/.../sources`) so an external client
can talk to one provider in isolation, or ask Orinuno to merge candidates
across all of them.

This split is deliberately separate from the legacy "demo / one big
controller" surface that grew organically. The old paths still work as
deprecation aliases for at least one minor release.

## At-a-glance

| Path | Purpose |
| --- | --- |
| `GET  /api/v1/sources` | List supported providers + capabilities + auth state |
| `POST /api/v1/sources/{provider}/decode` | Stateless ad-hoc decode for any of `kodik`, `sibnet`, `aniboom`, `jutsu` |
| `GET  /api/v1/sources/jutsu/stream` | Pass-through proxy for jut.su CDN URLs (PROXY-1) |
| `GET  /api/v1/anime/{contentId}/episodes/{s}/{e}/sources` | Ranked multi-provider candidates for an episode |
| `GET  /api/v1/anime/by-kinopoisk/{kpId}/episodes/{s}/{e}/sources` | Same, but resolves `contentId` from `kinopoiskId` first |

Deprecated aliases (still working):

| Old path | Replacement |
| --- | --- |
| `POST /api/v1/providers/decode` | `POST /api/v1/sources/{provider}/decode` |
| `GET /api/v1/providers/jutsu/stream` | `GET /api/v1/sources/jutsu/stream` |
| `GET /api/v1/sources/{contentId}/{s}/{e}` | `GET /api/v1/anime/{contentId}/episodes/{s}/{e}/sources` |

The deprecation aliases live in the same Spring controllers and route
through the same handlers, so behaviour is identical. They are marked
`@Deprecated` in the OpenAPI snapshot.

## `GET /api/v1/sources` — capabilities

Returns one entry per provider with the operations it exposes, whether
credentials are required for premium content, and whether they are
currently configured. Safe to call without authentication.

```sh
curl -sS http://localhost:8085/api/v1/sources | jq
```

```json
{
  "providers": [
    {
      "id": "kodik",
      "displayName": "Kodik",
      "description": "Russian-language anime/series/movies aggregator. Primary source.",
      "operations": ["search", "list", "embed", "decode", "calendar"],
      "credentialsRequired": false,
      "credentialsConfigured": false,
      "notes": "Token-driven; configured via KODIK_TOKEN env var. See /api/v1/parse and /api/v1/kodik for the full surface."
    },
    {
      "id": "jutsu",
      "displayName": "JutSu",
      "description": "JutSu free-and-premium anime player. Premium content needs a jut.su+ account.",
      "operations": ["decode", "stream"],
      "credentialsRequired": true,
      "credentialsConfigured": true,
      "notes": "JUTSU_USERNAME / JUTSU_PASSWORD configured — premium content will be decoded automatically."
    },
    { "id": "sibnet",  "displayName": "Sibnet",  "operations": ["decode"],          "credentialsRequired": false, "credentialsConfigured": false, "notes": "Stateless — no credentials needed." },
    { "id": "aniboom", "displayName": "Aniboom", "operations": ["decode"],          "credentialsRequired": false, "credentialsConfigured": false, "notes": "Stateless — no credentials needed. Some episodes are geo-restricted." }
  ],
  "count": 4
}
```

Use `credentialsConfigured` from the demo UI / health dashboards to show
operators which providers are wired up vs which need env vars set.

## `POST /api/v1/sources/{provider}/decode` — per-source sandbox

Stateless decoder dispatch keyed by path segment. Returns a uniform
`ProviderDecodeResult` for every provider:

```json
{
  "success": true,
  "qualities": { "720": "https://...", "480": "https://..." },
  "format": "video/mp4",
  "errorCode": null
}
```

Supported segments (case-insensitive): `kodik`, `sibnet`, `aniboom`,
`jutsu`. Anything else returns HTTP 400 with `errorCode:
"UNSUPPORTED_PROVIDER:<value>"`.

```sh
# Sibnet
curl -sS -X POST http://localhost:8085/api/v1/sources/sibnet/decode \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://video.sibnet.ru/shell.php?videoid=12345"}' | jq

# Kodik (returns HLS — note the application/x-mpegURL format)
curl -sS -X POST http://localhost:8085/api/v1/sources/kodik/decode \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://kodik.info/serial/123/abc/720p"}' | jq
```

This endpoint is **stateless**: no DB write, no caching, no orchestration.
For production-grade ingestion that persists `kodik_content` /
`kodik_episode_variant` rows use `POST /api/v1/parse/search` and
`POST /api/v1/parse/decode/...` instead.

For the failure modes of the JutSu decoder specifically (`PREMIUM_REQUIRED`,
`PLAYER_MISSING`, `SOURCE_TAG_MISSING`, …) see the
[provider CDN block runbook](https://github.com/Samehadar/orinuno/blob/master/docs/runbooks/provider-cdn-block.md).

## `GET /api/v1/sources/jutsu/stream` — JutSu CDN proxy (PROXY-1)

Pass-through proxy for jut.su's Yandex CDN URLs. Mandatory because Yandex
signs URLs against the originating session — a URL the backend obtained
cannot be opened directly by a browser. See
[`docs/quirks-and-hacks.md`](https://github.com/Samehadar/orinuno/blob/master/docs/quirks-and-hacks.md)
→ "JutSu DLE auth + sticky cookies + 1 RPS hard cap" for the deep dive.

| Param | Required | Notes |
| --- | --- | --- |
| `url` | yes | Must be on a `*.yandexwebcache.org` host. Anything else returns HTTP 403 `host not whitelisted`. |
| `filename` | no | When present, the proxy adds `Content-Disposition: attachment; filename="..."; filename*=UTF-8''...` so the browser triggers a download instead of inline playback. RFC 6266 + RFC 5987. |

The legacy alias `GET /api/v1/providers/jutsu/stream` still works and
routes through the same handler.

## `GET /api/v1/anime/{contentId}/episodes/{season}/{episode}/sources`

Ranked multi-provider candidates for one episode (AP-7, ADR 0008).
Returns the merged `episode_source` + `episode_video` rows scored by
`MultiSourceRanker` — higher score = better choice (provider preference,
freshness, quality, decode reliability).

```sh
curl -sS 'http://localhost:8085/api/v1/anime/42/episodes/1/2/sources' | jq
```

```json
{
  "contentId": 42,
  "season": 1,
  "episode": 2,
  "count": 3,
  "candidates": [
    {
      "provider": "KODIK",
      "translatorId": "anilibria",
      "translatorName": "AniLibria.TV",
      "quality": "720",
      "videoUrl": "https://.../master.m3u8",
      "videoFormat": "application/x-mpegURL",
      "decodedAt": "2026-04-30T18:12:03",
      "decodeMethod": "REGEX",
      "decodeFailedCount": 0,
      "score": 100.0
    }
  ]
}
```

Optional `?prefer=ANIBOOM,KODIK,SIBNET,JUTSU` overrides the default
provider order. The first segment in the list wins all ties.

The legacy short path `GET /api/v1/sources/{contentId}/{season}/{episode}`
returns the exact same payload and is kept as a deprecated alias.

## `GET /api/v1/anime/by-kinopoisk/{kpId}/episodes/{s}/{e}/sources`

Same payload as the by-`contentId` variant, but lets external integrations
skip the `contentId` lookup. Returns `404 { "error": "kinopoiskId not found" }`
when no `kodik_content` row matches the supplied id.

```sh
curl -sS 'http://localhost:8085/api/v1/anime/by-kinopoisk/123456/episodes/1/2/sources' | jq
```

## Authentication

The sources tier intentionally stays **outside** `ApiKeyAuthFilter`'s gate
so the demo UI and embedded `<video>` tags can call it without an API
key. Lock it down at your reverse proxy if you ship Orinuno as a
publicly-reachable service.

## OpenAPI snapshot

The endpoints above are tagged `Sources` and `Multi-source` in
[`docs-site/openapi.json`](https://github.com/Samehadar/orinuno/blob/master/docs-site/openapi.json).
Refresh the snapshot with the workflow described in the
[docs-site README](https://github.com/Samehadar/orinuno/blob/master/docs-site/README.md#updating-the-openapi-snapshot)
after every API change.
