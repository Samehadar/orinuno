# Runbook: Enrichment scheduler hits Jikan / Shikimori 429

## Symptom

- Logs from `EnrichmentService` repeat:
  `META-1 client MAL failed for content_id=…: 429 Too Many Requests`.
- `kodik_content_enrichment.last_refreshed_at` for `source IN ('SHIKIMORI','MAL')` doesn't move.
- Outgoing request rate to `api.jikan.moe` / `shikimori.one` ≥ 5 req/sec.

## Detect

```bash
# Per-source freshness
mysql> SELECT source,
              MIN(last_refreshed_at) AS oldest,
              MAX(last_refreshed_at) AS newest,
              COUNT(*) AS rows
       FROM kodik_content_enrichment
       GROUP BY source;
```

```bash
# 429 rate from logs (last hour)
grep "429" /var/log/orinuno/orinuno-app.log | grep -c "META-1"
```

## Mitigate

1. **Throttle the scheduler immediately** — drop the per-tick batch size:
   ```yaml
   orinuno:
     enrichment:
       max-per-tick: 30          # was 200
       interval-minutes: 5       # serialise harder
   ```
2. Restart so `EnrichmentScheduler` picks up new config.
3. If a single source is rate-limiting (Jikan), disable just that client by removing it from
   `EnrichmentService`'s list (keep Shikimori + Kinopoisk running).

## Root-cause

- Jikan publishes a 3 req/sec hard cap (and a 60 req/min soft cap). Each `EnrichmentClient`
  fires on every content row with no per-host rate limiter today (TD-2 follow-up).
- Shikimori uses a 5 req/sec cap; we hit it when seeding from a new dump (DUMP-2 bootstrap).
- Bootstrap mode (DUMP-2) deliberately ingests thousands of rows in a few minutes, then
  enrichment fans out a few seconds later — burst pattern.

## Prevent

- Wire a `RateLimiter` into each `EnrichmentClient` (Resilience4j, 3 req/sec for Jikan,
  5 req/sec for Shikimori). Tracked under TD-2 / IDEA-10.
- Schedule enrichment only for content rows that have been stable for ≥1h (don't enrich
  during bootstrap).
- Keep the orchestrator strictly serial per content (the current `flatMap` already does this);
  don't introduce parallelism without per-host limiting first.
