---
title: Embed-link Shortcut
description: Resolve a Kodik player iframe URL by external id without going through the parser pipeline.
---

`GET /api/v1/embed/{idType}/{id}` is a thin REST wrapper around Kodik's
internal `/get-player` helper. It returns the resolved
`https://kodikplayer.com/...` iframe URL (plus minimal metadata) without
touching the database, the parser pipeline, or the video decoder.

Use this when you only need an iframe URL — for example, the IDEA-CAL-3
"Next episode" widget or external embedders that have only a Shikimori /
Kinopoisk / IMDb id and want to drop a player onto a page. If you also
need to ingest the content (persist seasons, episodes, translations,
material data) use `POST /api/v1/parse/search` instead.

## Quick example

```sh
curl -sS http://localhost:8085/api/v1/embed/shikimori/20 | jq
```

```json
{
  "idType": "shikimori",
  "requestedId": "20",
  "normalizedId": "20",
  "embedLink": "https://kodikplayer.com/serial/73959/68e2e57cb95f7fb93655637acaca26c2/720p",
  "mediaType": "serial"
}
```

When `orinuno.security.api-key` is configured you must pass
`-H 'X-API-KEY: <secret>'`.

## Supported id types

| `idType` slug          | Kodik query key         | Notes |
| ---------------------- | ----------------------- | --- |
| `shikimori`            | `shikimoriID`           | Numeric Shikimori id (e.g. `20` for Naruto). |
| `kinopoisk`            | `kinopoiskID`           | Numeric Kinopoisk id. |
| `imdb`                 | `imdbID`                | IMDb id. The `tt` prefix is **added automatically** when missing — both `0903747` and `tt0903747` work. The chosen value is echoed back in `normalizedId`. |
| `mdl`                  | `mdlID`                 | MyDramaList id. |
| `kodik`                | `ID`                    | Native Kodik id. **Must** be supplied as `serial-{n}` or `movie-{n}` (matches AnimeParsers). |
| `worldart_animation`   | `worldart_animation_id` | World Art animation id. |
| `worldart_cinema`      | `worldart_cinema_id`    | World Art cinema id. |

Slug parsing is case-insensitive and accepts kebab-case
(`worldart-animation` → `WORLDART_ANIMATION`). The canonical, JSON-serialised
form is `snake_case`.

## Response shape

| Field          | Type            | Description |
| -------------- | --------------- | --- |
| `idType`       | string (enum)   | The id type as parsed from the path, in canonical `snake_case`. |
| `requestedId`  | string          | The id the caller provided, before normalisation. |
| `normalizedId` | string          | The id sent to Kodik. Differs from `requestedId` only for `imdb` (auto `tt` prefix). |
| `embedLink`    | string          | Absolute `https://kodikplayer.com/...` URL. Always normalised to `https`, even if Kodik returns a protocol-relative `//...`. |
| `mediaType`    | string \| null  | `serial` for multi-episode content, `video` for movies / single videos, `null` when the URL does not match the expected `kodikplayer.com` pattern. Soft signal — caller should still treat the link as opaque. |

## Status codes

| Status | Meaning |
| ------ | --- |
| `200`  | Embed link resolved. |
| `400`  | Unknown `idType` slug, or `id` is empty. |
| `401`  | Missing / wrong `X-API-KEY` (only when api-key auth is enabled). |
| `404`  | Kodik returned `"found": false` — no player exists for the supplied id. |
| `502`  | Kodik returned a non-recoverable upstream error (rate limit, malformed payload, …). |
| `503`  | The token registry is empty or every known token was rejected by Kodik. |

## How it differs from `/parse/search`

| Concern                         | `/api/v1/embed/{idType}/{id}` | `POST /api/v1/parse/search` |
| ------------------------------- | ----------------------------- | --- |
| Upstream call                   | `GET /get-player` (1 request) | `POST /search` (1 request, but with rich payload) |
| Returns                         | Single embed iframe URL       | Full content + season + episode tree, persisted to MySQL |
| DB writes                       | None                          | Inserts/upserts `kodik_content` + `kodik_episode_variant` |
| Triggers `KodikVideoDecoderService` | No                        | Yes (when called via `/parse/decode`) |
| Use case                        | "I just need an iframe URL"   | "I want to ingest this content into the catalog" |

Both paths share the same `KodikTokenRegistry`, the same per-minute
rate-limiter (`KodikApiRateLimiter`), and the same drift-detection
policy.

## Source / inspiration

The endpoint mirrors AnimeParsers 1.16.1's `KodikParser.get_embed_link`
(`e442365`), which made the previously private `_link_to_info` helper
public and added support for the `mdl`, `kodik`, `worldart_animation`,
and `worldart_cinema` id types. See `BACKLOG.md` → `IDEA-AP-6` for the
design notes.
