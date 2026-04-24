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
`8081`). Scrape path: `/actuator/prometheus`. Interesting series:

- `kodik_api_requests_total{endpoint,status}` — per-endpoint call counts.
- `kodik_decoder_attempts_total{outcome}` — decoder successes vs failures.
- `http_server_requests_seconds_count` — standard Micrometer HTTP metrics
  for the REST API.

Add them to your Grafana dashboard as needed; no reference dashboard is
shipped yet.

## Schema drift telemetry

`GET /api/v1/health/schema-drift` returns a JSON map keyed by
`DTO.field`, with the count of occurrences and the last-seen timestamp.
An empty object means no drift since the process started.

This is the single most useful endpoint to watch over time — a sudden jump
in the count is the earliest signal that Kodik changed something.

## Logs

- Drift: `[SCHEMA DRIFT]` tag at `WARN` level.
- Decoder: `KodikVideoDecoderService` logs each attempt at `DEBUG`, each
  failure at `WARN`.
- Rate limiting: `KodikApiRateLimiter` logs at `INFO` when the bucket is
  exhausted.

## Related

- [Schema drift](/orinuno/architecture/schema-drift/)
- [Background tasks](/orinuno/operations/background-tasks/)
