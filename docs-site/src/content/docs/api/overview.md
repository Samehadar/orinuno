---
title: API Overview
description: Base URLs, authentication, versioning, pagination, and error handling for the Orinuno REST API.
---

All public endpoints live under `/api/v1/`. Controllers return
`ResponseEntity<T>` or `Mono<ResponseEntity<T>>`, responses are JSON, and
request bodies are JSON or `application/x-www-form-urlencoded` where noted.

## Base URL

| Environment | Base URL |
| --- | --- |
| Docker Compose | `http://localhost:8085` |
| `mvn spring-boot:run` | `http://localhost:8080` |

## Authentication

Authentication is **optional** and off by default.

- When `orinuno.security.api-key` is empty, every endpoint is open.
- When set, `ApiKeyAuthFilter` requires the `X-API-KEY` header on every
  request to `/api/v1/content`, `/api/v1/parse`, `/api/v1/export`,
  `/api/v1/download`, `/api/v1/kodik`, `/api/v1/calendar`, and
  `/api/v1/embed`.
- `/api/v1/sources/*`, `/api/v1/anime/*`, and `/api/v1/providers/*`
  (legacy alias) are intentionally **not** gated so the demo UI and
  embedded `<video>` tags can call them without an API key. Lock them
  down at your reverse proxy if you ship Orinuno publicly.
- `/api/v1/health/*` is always open.
- `/swagger-ui.html` and `/v3/api-docs` are not protected by the API key —
  protect them at a reverse proxy if you deploy publicly.

Example:

```sh
curl -sS -H 'X-API-KEY: your-secret' http://localhost:8085/api/v1/content
```

## Versioning

The URL prefix (`/api/v1/`) is the only version marker. Breaking changes
bump the prefix; additive changes stay on `v1`. Responses use ISO 8601 for
timestamps and stay compatible with standard JSON parsers.

## Pagination

List endpoints accept four query parameters:

| Parameter | Type | Default | Notes |
| --- | --- | --- | --- |
| `page` | int | `0` | 0-based index |
| `size` | int | `20` | 1 to 100 |
| `sortBy` | string | `id` | Whitelisted column |
| `order` | `ASC` \| `DESC` | `DESC` | Whitelisted direction |

`sortBy` and `order` are validated against a hard-coded whitelist before
being passed to MyBatis. Unknown values are rejected with HTTP 400.

Response envelope:

```json
{
  "content": [ /* items */ ],
  "page": 0,
  "size": 20,
  "totalElements": 42,
  "totalPages": 3
}
```

## Error handling

Errors use the standard Spring `ProblemDetail` shape when the controller
throws a typed exception. For ad-hoc errors, the body is
`{ "error": "message" }` with an appropriate HTTP status. Common codes:

| Status | Meaning |
| --- | --- |
| `400` | Validation error or invalid `sortBy` / `order` |
| `401` | Missing or invalid `X-API-KEY` |
| `404` | Content or variant not found |
| `429` | Kodik rate limit hit — retry later |
| `500` | Upstream or internal error |

## API tiers

| Tier | Path prefix | When to use |
| --- | --- | --- |
| **Sources** (per-provider) | `/api/v1/sources/...` | Talk to one provider in isolation: list capabilities, decode a single URL, stream a JutSu CDN video. Open-source-friendly entry point for external clients. |
| **Anime resource** | `/api/v1/anime/{id}/episodes/.../sources` | Ask Orinuno to merge candidates across providers and return a ranked list. Resource-oriented, friendly to REST consumers. |
| **Catalog & ingestion** | `/api/v1/content`, `/api/v1/parse`, `/api/v1/export` | Persisted Kodik content and the parser pipeline. Protected by `X-API-KEY` when configured. |
| **Kodik direct** | `/api/v1/kodik/...`, `/api/v1/embed/...` | Thin wrappers over Kodik REST endpoints. |
| **Calendar / health** | `/api/v1/calendar/...`, `/api/v1/health/...` | Operational surfaces. |

The `Sources` and `Anime` tiers were introduced as part of the
API/module split. The legacy `POST /api/v1/providers/decode`,
`GET /api/v1/providers/jutsu/stream`, and
`GET /api/v1/sources/{contentId}/{s}/{e}` paths still work as
deprecation aliases — see the [Sources guide](/orinuno/api/sources/) for
the mapping table.

## Where to go next

- [Sources & Multi-Provider API](/orinuno/api/sources/) — per-source
  endpoints + ranked candidates + deprecation aliases.
- [Embed-link Shortcut](/orinuno/api/embed/) — resolve Kodik iframe URLs
  by external id without going through the parser pipeline.
- [API Reference](/orinuno/api/reference/) — generated from the OpenAPI
  snapshot (`openapi.json`). See the [docs-site README](https://github.com/Samehadar/orinuno/blob/master/docs-site/README.md#updating-the-openapi-snapshot) for how to refresh it.
- [Quick Start](/orinuno/getting-started/quick-start/) — smoke test
  commands.
- [Configuration](/orinuno/getting-started/configuration/) — enable the API
  key, tune rate limits.
