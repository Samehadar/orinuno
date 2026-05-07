---
title: Monitoring
description: Health endpoints, Prometheus metrics, and schema-drift telemetry exposed by Orinuno.
---

Orinuno exposes a small set of health endpoints under `/api/v1/health/*`
plus Spring Boot Actuator for Prometheus metrics on the management port.

## Health endpoints

All three are always open (no API-key needed):

| Path | Purpose |
| --- | --- |
| `GET /api/v1/health` | Liveness — returns `UP` when the application context is ready |
| `GET /api/v1/health/decoder` | Decoder statistics — success count, failure count, last success/failure, rolling success rate |
| `GET /api/v1/health/proxy` | Proxy pool status — active, disabled, failed counts |

Example:

```sh
curl -sS http://localhost:8085/api/v1/health/decoder | jq
```

## Prometheus

Actuator exposes Prometheus metrics on the management port (default
`8081`). Scrape path: `/actuator/prometheus`.

### Parse-request log (Phase 2.5)

Added in TD-PR-5 to make the async parse-request worker observable.
Without these the only way to spot a stuck `RequestWorker` was tailing
logs.

| Series | Type | Labels | What it tells you |
| --- | --- | --- | --- |
| `orinuno_parse_requests` | gauge | `status=PENDING\|RUNNING\|DONE\|FAILED` | Current row count per status. Sustained high `PENDING` ⇒ worker isn't draining. |
| `orinuno_parse_request_worker_tick_seconds` | timer | `quantile=0.5,0.95,0.99` | Wall-clock latency of one `RequestWorker.tick()` (claim → process → mark terminal). |
| `orinuno_parse_request_processing_seconds` | timer | `outcome=DONE\|FAILED`, `quantile=…` | Time from claim to terminal status, split by outcome. |
| `orinuno_parse_requests_completed_total` | counter | `outcome=DONE\|FAILED` | Lifetime terminal-state transitions (use `rate()` for throughput). |

### jut.su L1 cache + live-fallback (ADR 0016 P1a)

Added with the jut.su L1 cache rollout. Surfaced from
`JutsuLiveFallbackService` (`/api/v1/sources/jutsu/{anime,episode,catalog,search}`)
and the staleness header.

| Series | Type | Labels | What it tells you |
| --- | --- | --- | --- |
| `jutsu_live_fallback_total` | counter | `outcome=hit\|miss\|upstream_error\|rate_limited\|disabled\|negative_cache` | Live-fallback dispatcher outcome counts. Use `rate(jutsu_live_fallback_total{outcome="upstream_error"}[5m])` to spot a jut.su outage; sustained `outcome="rate_limited"` means a noisy consumer or too-tight `JUTSU_LIVE_FALLBACK_RPS`. |

Also reflected in response shape: every jut.su API response carries an
`X-Sync-Stale-Seconds` header (seconds since the last successful full
crawl). Watch it in dashboards or browser devtools as a quick "is the
sync worker still ticking?" signal.

### Other interesting series

- `kodik_tokens_count{tier=stable|unstable|legacy|dead}` — token-pool depth per tier.
- `http_server_requests_seconds_count` — standard Micrometer HTTP metrics for the REST API.
- `kodik_api_requests_total{endpoint,status}` — per-endpoint Kodik client call counts.
- `kodik_decoder_attempts_total{outcome}` — decoder successes vs failures.

## Local Grafana stack

The repo ships a Prometheus + Grafana stack pre-wired to scrape orinuno
and load a reference dashboard. Both services live behind the
`observability` Compose profile, so plain `docker compose up` stays
minimal.

### Bring it up

```sh
docker compose --profile observability up -d prometheus grafana
```

URLs once both are up:

| Service | URL | Notes |
| --- | --- | --- |
| Prometheus | http://localhost:9090 | Scrapes `/actuator/prometheus` every 5 s |
| Grafana | http://localhost:3001 | `admin / admin`, anonymous `Viewer` allowed |
| Dashboard | [`orinuno → orinuno — parse requests`](http://localhost:3001/d/orinuno-parse-requests) | Auto-provisioned |

Prometheus is configured with **two** static targets:

- `app:8081` — when orinuno itself runs as the `app` Compose service.
- `host.docker.internal:8081` — when orinuno runs on the developer's
  host (e.g. `mvn spring-boot:run`).

Whichever target is live shows `up=1`; the other shows `up=0` and is
ignored by the dashboard (which aggregates over the `service=orinuno`
label).

### Files

```
observability/
├── prometheus/
│   └── prometheus.yml                    # scrape config (5s interval)
└── grafana/
    ├── provisioning/
    │   ├── datasources/datasource.yml    # Prometheus auto-wired
    │   └── dashboards/dashboards.yml     # auto-loads /var/lib/grafana/dashboards
    └── dashboards/
        └── orinuno-parse-requests.json   # reference dashboard
```

The dashboard JSON is editable in Grafana (provisioning leaves
`allowUiUpdates: true`); commit changes back to the file to keep the
preset version up to date.

## Schema drift telemetry

`GET /api/v1/health/schema-drift` returns a JSON map keyed by context
label (e.g. `KodikSearchResponse`, `MaterialData[anime-serial]`), with
the unknown fields, first/last-seen timestamps, and `hitCount` of
responses that drifted. An empty object means no drift since the process
started.

This is the single most useful endpoint to watch over time — a sudden
jump in `hitCount` or a new context label is the earliest signal that
Kodik changed something. See
[architecture → schema drift](/orinuno/architecture/schema-drift/) for
full coverage details and the `orinuno.drift.*` config keys.

## Logs

- Drift: `[SCHEMA DRIFT]` tag at `WARN` level.
- Decoder: `KodikVideoDecoderService` logs each attempt at `DEBUG`, each
  failure at `WARN`.
- Rate limiting: `KodikApiRateLimiter` logs at `INFO` when the bucket is
  exhausted.

## Related

- [Schema drift](/orinuno/architecture/schema-drift/)
- [Background tasks](/orinuno/operations/background-tasks/)
