---
title: Schema Drift Detection
description: How Orinuno catches silent Kodik API changes — raw-map first responses, a reusable DriftDetector, configurable sampling, and a health endpoint that surfaces drift in real time.
---

The Kodik API has no versioning and no stability guarantees. Fields appear,
rename, and disappear without notice. Orinuno treats that as a first-class
concern: every response is captured raw, compared against the known DTO
shape, and drift is recorded before any typed conversion happens.

## Architecture

Drift detection lives in its own domain-neutral package, `com.orinuno.drift`:

| Class | Role |
| --- | --- |
| `DriftDetector` | Generic engine. Given a raw `Map<String, Object>` and an expected DTO class, records unknown JSON keys. Also implements the envelope + `results[]` pattern used across Kodik. |
| `DriftRecord` | Immutable view of what was seen for a given context: unknown fields, first/last seen, `hitCount`. |
| `DtoFieldExtractor` | Reflection helper that produces the canonical set of known JSON keys for a DTO (Java Beans + records, snake_case via Jackson fallback, `@JsonProperty` aliases). Results are cached. |
| `DriftSamplingProperties` | Config: `enabled`, `itemSampling.mode` (`NONE` / `FIRST_N` / `ALL`), `itemSampling.limit`. |

`KodikResponseMapper` is a thin facade around a single Spring-managed
`DriftDetector` bean — the same instance sees runtime traffic, probe
traffic, and paginated `/list` calls, so the drift map is a
single coherent view of what Kodik has returned since the process started.

## Pipeline for a Kodik call

Every call through `KodikApiClient` goes through the same shape:

1. **Receive as `Map<String, Object>`** — the WebClient decoder is bound
   to `Map.class`, not to the typed DTO.
2. **Envelope check** — `DriftDetector` compares the top-level keys of the
   raw map to `DtoFieldExtractor.knownJsonFields(EnvelopeDTO.class)` and
   records unknowns under a context label (e.g. `KodikSearchResponse`).
3. **Item sampling** — for envelope-with-`results[]` shapes, the first N
   items are checked against the inner DTO (`KodikSearchResponse.Result`
   or a typed reference DTO). `N` comes from `orinuno.drift.item-sampling`.
4. **Nested `material_data`** — for `/search` and `/list`, the same first
   N results are also sampled against `KodikMaterialDataDto`, keyed as
   `MaterialData[<content-type>]` so a drama-only field showing up in an
   anime result is obvious in the report.
5. **Convert** — only after sampling, `ObjectMapper.convertValue(raw, DTO.class)` produces the typed object. Unknown fields are dropped at
   that step, but the raw copy stayed in the drift record.

Each HTTP response increments `hitCount` by exactly one, regardless of
how many items inside it drifted — the metric answers "how many
responses drifted", not "how many items drifted".

## Coverage

The table is exhaustive as of 2026-04-25:

| Entry point | Covered by |
| --- | --- |
| `/search` (typed) | `KodikApiClient.search()` → mapper |
| `/search` (raw) | `KodikApiClient.searchRaw()` → `mapAndDetectChanges` |
| `/list` (raw + `listAll` pagination) | `KodikApiClient.listRaw()` → `doOnNext` |
| `/translations/v2`, `/genres`, `/countries`, `/years`, `/qualities/v2` | `KodikApiClient.translations/genres/...` → typed reference mapper (envelope + item) |
| `material_data` (first-N results) | `KodikResponseMapper.sampleMaterialData` |
| Token probes (`/search`, `/list`) | `KodikTokenValidator.probe()` |

A known coupling is documented in `KodikTokenValidator`: probes share the
same `DriftDetector` as runtime traffic. If probe-isolated stats become
useful, a future `TrafficAnalyzer` service can route probes through a
dedicated detector without touching the probe code path.

## Configuration

```yaml
orinuno:
  drift:
    enabled: true          # ORINUNO_DRIFT_ENABLED
    item-sampling:
      mode: FIRST_N        # ORINUNO_DRIFT_ITEM_SAMPLING_MODE — NONE | FIRST_N | ALL
      limit: 10            # ORINUNO_DRIFT_ITEM_SAMPLING_LIMIT — only used by FIRST_N
```

- `enabled: false` turns the detector into a no-op. `detectedDrifts`
  stays empty, no reflection is performed.
- `mode: NONE` keeps the envelope check but skips item sampling — useful
  if a single large response is costly to scan.
- `mode: ALL` sanity-checks everything — fine for small reference
  responses, expensive for 1000-item `/list` pages.

## Surfacing drift

`GET /api/v1/health/schema-drift` returns a JSON map keyed by context
label. Values carry the set of unknown keys, first/last seen timestamps,
and the number of responses that hit that context.

Example after Kodik adds a new field under `material_data` for dramas:

```json
{
  "MaterialData[drama]": {
    "unknownFields": ["new_drama_metric"],
    "firstSeen": "2026-04-25T08:12:31Z",
    "lastSeen":  "2026-04-25T09:47:02Z",
    "hitCount":  42
  }
}
```

In a healthy system this is an empty object; if it grows, it is the
signal to update the matching DTO (usually adding a component to the
Java record or a field to the DTO class) and redeploy.

## Why raw-map-first

Two common alternatives both fail for our use case:

- **Strict typed binding** — `@JsonIgnoreProperties(ignoreUnknown = false)` turns drift into a deserialization error and a 5xx for the
  consumer. That is noisy and paints drift as a crash instead of a
  signal.
- **Permissive typed binding** — `@JsonIgnoreProperties(ignoreUnknown = true)` silently drops new fields. That is exactly what we do not
  want: the whole reason this project exists is to detect those silent
  drops.

Raw-map-first gives us permissive behaviour at runtime *plus* full
observability. We never break the consumer because Kodik added a field,
and we always know when they did.

## Related

- [Kodik API flow](/orinuno/architecture/kodik-api-flow/)
- [Operations → Monitoring](/orinuno/operations/monitoring/)
