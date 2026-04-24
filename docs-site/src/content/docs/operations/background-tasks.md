---
title: Background Tasks
description: Summary of every scheduled job in Orinuno — what runs, how often, and what it touches.
---

Orinuno keeps a short list of scheduled jobs. All of them live inside
`ParserService` and use the Spring scheduler.

| Task | Interval | Purpose |
| --- | --- | --- |
| `refreshExpiredLinks` | `orinuno.decoder.refresh-interval-ms` (default 1h) | Re-decode mp4 links older than `link-ttl-hours` |
| `retryFailedDecodes` | Same interval, +30 min offset | Retry variants where the previous decode failed |

## Ordering

The two jobs run on the same scheduler but intentionally offset by half an
interval. This smooths the outbound call rate — we never hit the Kodik API
with a full refresh batch plus a full retry batch at the same instant.

## Observability

- Each job logs a summary line at the start and end (`count=N`).
- Decoder success and failure are tracked per call by
  `DecoderHealthTracker` and surfaced via `GET /api/v1/health/decoder`.

## Disabling

There is no runtime toggle. If you need to stop the schedulers — for
example, to let a manual migration finish — comment out the `@Scheduled`
annotation in source or spin up the service with Spring's scheduler
disabled (`spring.task.scheduling.pool.size=0`).

## Related

- [TTL refresh](/orinuno/operations/ttl-refresh/)
- [Monitoring](/orinuno/operations/monitoring/)
