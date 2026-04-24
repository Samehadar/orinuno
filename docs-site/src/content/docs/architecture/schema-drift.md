---
title: Schema Drift Detection
description: How KodikResponseMapper catches silent API changes — raw-map parsing, reflection-based field discovery, and a health endpoint that surfaces drift in real time.
---

The Kodik API has no versioning and no stability guarantees. Fields appear,
rename, and disappear without notice. Orinuno treats that as a first-class
concern: every response is captured raw, compared against the known DTO
shape, and drift is logged before any conversion happens.

## Pipeline

Every call to `KodikApiClient` passes through the same mapper:

1. **Receive as `Map<String, Object>`** — the WebClient body decoder is
   bound to `Map.class`, not to the typed DTO.
2. **Discover known fields** — `KodikResponseMapper` walks the target DTO
   via reflection, collecting every declared field name plus any
   `@JsonProperty("…")` alias.
3. **Diff raw keys vs known** — anything in the raw map that is not in the
   known set is unknown-to-us.
4. **Log and record** — unknown keys produce a `WARN` log line tagged
   `[SCHEMA DRIFT]` and increment a counter keyed by DTO class + field
   name.
5. **Convert** — only after the diff, `ObjectMapper.convertValue(raw, DTO.class)` produces the typed DTO. Unknown fields are dropped at this
   point, but the raw copy stays in logs for offline inspection.

## Surfacing drift

Drift counters are exposed via `GET /api/v1/health/schema-drift`. The
response lists every `(DTO, field)` pair that has ever been flagged, with
the occurrence count and the last-seen timestamp. In a healthy system this
is an empty object; if it grows, it is a signal to update the DTOs.

## Why raw-map-first

There are two common alternatives, and both fail for our use case:

- **Strict typed binding** — `@JsonIgnoreProperties(ignoreUnknown = false)` turns drift into a deserialization error and a 5xx for the consumer. That is noisy and paints drift as a crash instead of a signal.
- **Permissive typed binding** — `@JsonIgnoreProperties(ignoreUnknown = true)` silently drops new fields. That is exactly what we do not want: the whole reason this project exists is to detect those silent drops.

Raw-map-first gives us permissive behaviour at runtime *plus* observability.
We never break the consumer because Kodik added a field, and we always know
when they did.

## Related

- [Kodik API flow](/orinuno/architecture/kodik-api-flow/)
- [Operations → Monitoring](/orinuno/operations/monitoring/)
