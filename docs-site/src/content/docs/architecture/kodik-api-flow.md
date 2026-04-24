---
title: Kodik API Flow
description: How Orinuno talks to kodik-api.com — token-bucket rate limiter, raw-response-first parsing, schema drift detection, and MySQL upserts.
---

Every call to `kodik-api.com` goes through the same pipeline: a token-bucket
rate limiter, a raw-response-first parser, schema drift detection, and a
COALESCE-aware upsert into MySQL.

![Kodik API flow](/orinuno/diagrams/1_kodik_api_flow.svg)

## Rate limiter

`KodikApiRateLimiter` wraps each outbound call in a semaphore-based token
bucket:

- Bucket size = `orinuno.parse.rate-limit-per-minute`.
- A scheduler refills permits at a steady interval of `60_000 / max-permits` ms.
- If no permit is available, the caller gets back a rate-limit error and is
  expected to retry. The controller layer surfaces this as HTTP 429.

This is a per-process limiter — if you run multiple replicas behind a load
balancer, budget the total quota across them.

## Raw-response-first

We never bind the response directly to a typed DTO on the wire. Every call
lands in a `Map<String, Object>`, and only then gets converted:

1. `WebClient` returns `Map<String, Object>` for the body.
2. `KodikResponseMapper.mapAndDetectChanges(raw, DTO.class)` extracts the set
   of known fields by reflection (plus `@JsonProperty` aliases).
3. Unknown keys are logged as `[SCHEMA DRIFT]` and recorded in a counter that
   feeds `/api/v1/health/schema-drift`.
4. The typed DTO is produced via `ObjectMapper.convertValue(raw, DTO.class)`.

This preserves data even when Kodik adds new fields we do not know about
yet. See [Schema drift](/orinuno/architecture/schema-drift/) for the full
story.

## Domain note

The base URL is `https://kodik-api.com` — with a hyphen. Do not use
`kodikapi.com`; it is a different host. All Kodik endpoints are `POST`,
never `GET`.

## Endpoints covered

| Endpoint | DTO | Notes |
| --- | --- | --- |
| `/search` | `KodikSearchResponse` | 70+ filter parameters |
| `/list` | `KodikListResponse` | Cursor-based pagination via `next_page_url` |
| `/translations/v2` | `KodikTranslationsResponse` | Voice vs subtitle metadata |
| `/genres` | `KodikGenresResponse` | Reference data, cacheable |
| `/countries` | `KodikCountriesResponse` | Reference data |
| `/years` | `KodikYearsResponse` | Reference data |
| `/qualities/v2` | `KodikQualitiesResponse` | Reference data |

## Related

- [Schema drift](/orinuno/architecture/schema-drift/)
- [Operations → TTL refresh](/orinuno/operations/ttl-refresh/)
- [API overview](/orinuno/api/overview/)
