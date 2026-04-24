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
  `/api/v1/hls`, `/api/v1/download`, and `/api/v1/stream`.
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

## Where to go next

- [API Reference](/orinuno/api/reference/) — generated from the OpenAPI
  snapshot (`openapi.json`). See the [docs-site README](https://github.com/Samehadar/orinuno/blob/master/docs-site/README.md#updating-the-openapi-snapshot) for how to refresh it.
- [Quick Start](/orinuno/getting-started/quick-start/) — smoke test
  commands.
- [Configuration](/orinuno/getting-started/configuration/) — enable the API
  key, tune rate limits.
