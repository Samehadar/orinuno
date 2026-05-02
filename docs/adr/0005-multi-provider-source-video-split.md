# ADR 0005 — Split `kodik_episode_variant` into `episode_source` + `episode_video` for multi-provider

- **Status**: Accepted (implementation deferred — phased rollout)
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → PLAYER-1 (LARGE), PLAYER-2 (Aniboom), PLAYER-3 (Sibnet), PLAYER-4 (JutSu), PLAYER-5 (Shikimori), [docs/quirks-and-hacks.md](../quirks-and-hacks.md)

## Context

orinuno today only knows about Kodik. Every row in `kodik_episode_variant` carries:

- The episode coordinates (`content_id`, `season`, `episode`, `translation_id`).
- A Kodik-specific URL (`kodik_link`).
- A decoded MP4 / HLS URL (`mp4_link`, `mp4_link_decoded_at`, `decode_method`).
- A retry counter (`decode_failed_count`, `decode_last_error`).

The plan (PLAYER-2 .. PLAYER-5) wants to add Aniboom, Sibnet, JutSu and Shikimori as **alternative providers**. Each provider:

- Has its own iframe / source URL.
- Has its own decode pipeline (different ROT shifts, different player JS layouts, sometimes a completely different protocol — Aniboom uses MPEG-DASH, Sibnet uses direct MP4 with anti-hotlink headers, etc.).
- Has its own TTL (Aniboom CDN tokens expire faster than Kodik; Sibnet has no expiry but its anti-hotlink headers do).
- May have multiple qualities, multiple language tracks, multiple subtitles streams.

If we keep cramming everything into `kodik_episode_variant`:

- The table name is misleading (it would contain non-Kodik rows).
- The `kodik_link` column is misleading (Aniboom / Sibnet links live in different shape).
- The `(content_id, season, episode, translation_id)` UNIQUE constraint is wrong — `translation_id` is a Kodik concept; Aniboom doesn't have it. Two providers serving the same translation must coexist as separate rows.
- The decoder retry / TTL columns are provider-specific (Aniboom's failure modes don't share a common retry strategy with Kodik's).

A "spread out" workaround (one table per provider — `kodik_episode_variant`, `aniboom_episode_variant`, `sibnet_episode_variant`) avoids the schema problem but pushes the complexity into every consumer (each consumer would need a UNION query to find "all sources for episode X" and a per-provider switch to decode).

## Decision

**Adopt a normalized `episode_source` + `episode_video` schema.** Phase the rollout so we don't break the running system.

### Target schema

```sql
-- Provider-agnostic episode coordinates. One row per (content, season, episode, translator,
-- provider) tuple. Idempotent on re-fetch.
CREATE TABLE episode_source (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    content_id      BIGINT       NOT NULL,
    season          INT          NOT NULL,
    episode         INT          NOT NULL,
    translator_id   VARCHAR(64)  NULL,            -- Kodik translation_id, Aniboom voice_id, etc.
    translator_name VARCHAR(255) NULL,            -- denormalized for read-side queries
    provider        VARCHAR(32)  NOT NULL,        -- 'KODIK', 'ANIBOOM', 'SIBNET', 'JUTSU'
    source_url      VARCHAR(512) NOT NULL,        -- iframe / player URL, provider-specific shape
    source_type     VARCHAR(32)  NULL,            -- 'video', 'seria' (Kodik), or DASH/HLS hint
    discovered_at   DATETIME     NOT NULL,
    last_seen_at    DATETIME     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_episode_source (content_id, season, episode, translator_id, provider),
    KEY idx_provider (provider),
    KEY idx_content (content_id)
);

-- Provider-specific decoded video URL(s). One row per (source, quality) tuple. A single
-- episode_source can have multiple episode_video rows when the decoder returns multiple
-- qualities (Kodik typically returns 240/360/480/720; Aniboom may return master + variants).
CREATE TABLE episode_video (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    source_id          BIGINT       NOT NULL,
    quality            VARCHAR(16)  NOT NULL,    -- '720', '480', 'auto', 'master'
    video_url          VARCHAR(2048) NULL,
    video_format       VARCHAR(32)  NULL,        -- 'application/x-mpegURL', 'video/mp4', 'application/dash+xml'
    decoded_at         DATETIME     NULL,
    decode_method      VARCHAR(16)  NULL,        -- 'REGEX', 'SNIFF', 'PROVIDER_API'
    decode_failed_count INT         NOT NULL DEFAULT 0,
    decode_last_error  VARCHAR(512) NULL,
    ttl_seconds        INT          NULL,        -- provider-specific TTL hint, NULL = use default
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_quality (source_id, quality),
    CONSTRAINT fk_episode_video_source FOREIGN KEY (source_id) REFERENCES episode_source (id) ON DELETE CASCADE,
    KEY idx_decoded_at (decoded_at)
);
```

### Migration phases

1. **Phase A — Add new tables, dual-write from Kodik path.** Liquibase changeset creates the two new tables. `ParserService.persistDecode()` is updated to write to BOTH `kodik_episode_variant` (legacy) AND `episode_source` + `episode_video` (new). Read-side `/api/v1/export` keeps reading from `kodik_episode_variant`. Zero behaviour change for consumers; production runs both paths in parallel for a verification window.

2. **Phase B — Backfill.** A one-shot Spring `CommandLineRunner` (gated by `orinuno.migration.player1-backfill=true`, default `false`) walks every `kodik_episode_variant` row and upserts the equivalent `episode_source` + `episode_video` rows. Idempotent — safe to re-run. Runs to completion in a single JVM session; no background scheduler.

3. **Phase C — Read-side cutover.** `/api/v1/export`, `/api/v1/content/{id}/variants`, `/api/v1/parse/decode/...`, the streaming endpoints — all read-side queries — switch to the new schema. `kodik_episode_variant` keeps being written (still legacy backstop) but nothing reads it.

4. **Phase D — New provider integration.** PLAYER-3 (Sibnet) lands first with provider-specific `SibnetSourceParser` + `SibnetDecoderService` writing to `episode_source` (provider='SIBNET') + `episode_video`. Then PLAYER-2 (Aniboom). Then PLAYER-4 (JutSu). Then PLAYER-5 (Shikimori — note Shikimori is a metadata index, not a video source — see ADR 0008).

5. **Phase E — Decommission `kodik_episode_variant`.** After two release cycles of dual-write + read from new schema with zero divergence reports, drop the dual-write code and stop writing the legacy table. Mark the table as deprecated for one more cycle, then drop with a Liquibase changeset.

### What stays the same

- `kodik_content` (content metadata) is unchanged. Sources point at content via `content_id`.
- `kodik_proxy`, `kodik_calendar_state`, `kodik_calendar_outbox`, `kodik_decoder_path_cache`, `orinuno_dump_state`, `orinuno_parse_request` — all unchanged.
- The Kodik-specific decoder path (`KodikVideoDecoderService`, `KodikDecodeOrchestrator`, `PlaywrightSniffDecoder`) is unchanged. PLAYER-1 only changes WHERE the decoded URL is persisted, not HOW it is decoded.
- The decode-method discriminator from DECODE-8 (`REGEX` / `SNIFF`) is preserved on `episode_video.decode_method`.

## Consequences

### Positive

- Multi-provider becomes a strictly additive change (write a new `<Provider>SourceParser` + `<Provider>DecoderService`, point them at the new tables, done).
- Consumers that want "the best quality URL for episode X regardless of provider" can do a single ranked query instead of N union queries.
- The decoder retry / TTL columns now live where the URL lives (`episode_video`), not in a misleading provider-shaped table.

### Negative

- Migration is large (~5 phases, ~3-6 weeks of careful rollout).
- Dual-write in Phase A doubles the per-decode write cost during the verification window. Mitigated by the fact that decode is bounded (2 small INSERTs is cheap).
- Read-side cutover in Phase C is a fan-out edit across `/api/v1/export`, `/api/v1/content`, `/api/v1/parse/decode/...`, streaming endpoints, MyBatis XMLs and tests. Mitigated by the dual-write phase: we can revert the read-side switch instantly without rolling back data.

### Neutral

- Performance: `episode_source JOIN episode_video` adds one join to every variant-listing query. Modern InnoDB handles this in microseconds with the indexes above.

## Implementation tracker

Each phase is a separate PR. The tracker lives in BACKLOG.md as PLAYER-1.A, PLAYER-1.B, etc.

| Phase | Status | PR | Notes |
|---|---|---|---|
| A — schema + dual-write | Pending | — | Liquibase changeset + `ParserService.persistDecode()` update + tests |
| B — backfill `CommandLineRunner` | Pending | — | gated, idempotent, run-once |
| C — read-side cutover | Pending | — | export + content + decode + stream endpoints |
| D — Sibnet (PLAYER-3) | Pending | — | first additional provider; validates the schema |
| D2 — Aniboom (PLAYER-2) | Pending | — | second additional provider; validates DASH path |
| D3 — JutSu (PLAYER-4) | Pending | — | third additional provider; validates direct-MP4 path |
| E — decommission legacy | Pending | — | drop `kodik_episode_variant` after 2 release cycles |
