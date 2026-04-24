---
title: TTL Refresh
description: How the scheduled refresher keeps CDN links fresh — batch size, interval, retry-failed pass, and the tracking column that powers it.
---

CDN links from `solodcdn.com` have a short TTL. Orinuno keeps them usable
with a scheduled refresher and a separate retry pass for previously failed
decodes.

![TTL refresh and retry](/orinuno/diagrams/4_ttl_refresh.svg)

## Two scheduled tasks

Both run inside `ParserService` on the Spring scheduler:

- `refreshExpiredLinks` — every `orinuno.decoder.refresh-interval-ms`
  (default 1 hour), at an offset of 0.
- `retryFailedDecodes` — same interval, offset by 30 minutes.

## `refreshExpiredLinks`

1. Query `kodik_episode_variant` for rows where `mp4_link IS NOT NULL` and
   `mp4_link_decoded_at < NOW() - INTERVAL link-ttl-hours HOUR`. Batch size
   is capped by `orinuno.decoder.refresh-batch-size` (default 50).
2. For each row, call `KodikVideoDecoderService.decode(kodikLink)`.
3. On success, `UPDATE mp4_link = ?, mp4_link_decoded_at = NOW()`.
4. On failure, leave the row untouched — it will be picked up by
   `retryFailedDecodes` on the next tick.

## `retryFailedDecodes`

1. Query variants where `mp4_link IS NULL AND kodik_link IS NOT NULL`,
   same batch size.
2. Decode with `Retry.backoff(maxRetries, 2s)` — exponential backoff starting
   at 2 seconds (2s, 4s, 8s, ...).
3. On success, update `mp4_link` and `mp4_link_decoded_at`.

## Knobs

| Property | Default | Notes |
| --- | --- | --- |
| `orinuno.decoder.link-ttl-hours` | 20 | Shorter than observed CDN TTL |
| `orinuno.decoder.refresh-interval-ms` | 3600000 | How often to check |
| `orinuno.decoder.refresh-batch-size` | 50 | Max links per cycle, protects the pool from flooding |
| `orinuno.decoder.max-retries` | 3 | Retry attempts per variant |

## Related

- [Video decoding](/orinuno/architecture/video-decoding/)
- [Monitoring](/orinuno/operations/monitoring/)
- [Configuration](/orinuno/getting-started/configuration/)
