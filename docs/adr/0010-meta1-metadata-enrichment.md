# ADR 0010 — META-1: metadata enrichment from Shikimori + Kinopoisk + MyAnimeList

- **Status**: Accepted (implementation deferred — independent of multi-provider work, but lower priority)
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → IDEA-AP-3, [ADR 0007](0007-player5-shikimori-metadata-not-source.md), reference: backend-master `kinopoisk-db-updater` module, AnimeParsers `parser_shikimori_async.py`

## Context

orinuno's `kodik_content` table holds whatever Kodik returns in `material_data`: title, year, kinopoisk_id, imdb_id, sometimes a poster URL, sometimes a list of genres. The data is sparse and inconsistent — Kodik populates `material_data` from whichever source they ingested, and the shape varies across `anime-serial`, `russian-movie`, `foreign-cartoon`, etc.

Operators and consumers want a richer content row:

- **Posters** at multiple sizes (catalog browse needs 200×300; episode page needs 800×1200).
- **Native-language title** (Kodik often only has the Russian title; consumers may want Japanese / Korean / English).
- **Synopsis** (Kodik's is often empty or 1-sentence; Shikimori has ~500-word descriptions).
- **Episode-level airing dates** (Kodik knows "season 1 has 24 episodes"; Shikimori knows "episode 1 aired 2023-04-05, episode 2 aired 2023-04-12, ...").
- **Score / rating** (cross-referenced from Shikimori, Kinopoisk, MyAnimeList — different audiences score differently).
- **Studio / director / voice cast** (none of which Kodik publishes consistently).

Three external sources cover this:

| Source | Coverage | API maturity | Auth required | Rate limit |
|---|---|---|---|---|
| **Shikimori** | anime + manga + ranobe (RU) | Mature (REST + GraphQL) | None for read | 5 req/sec |
| **Kinopoisk** (HD/Unofficial) | film + serial (RU) | Decent (REST) | API key | 100 req/day free |
| **MyAnimeList** (via Jikan) | anime + manga (EN/JP) | Mature (REST) | None | 3 req/sec |

backend-master already has a `kinopoisk-db-updater` module that knows how to talk to Kinopoisk. orinuno is open-source and standalone — it cannot import from backend-master, but it can mirror the same approach.

## Decision

**Implement metadata enrichment as an independent, opt-in service that reads `kodik_content` rows, fetches metadata from external sources, and writes to a new `kodik_content_enrichment` table. Do NOT mutate `kodik_content` — enrichment is additive, lossless, and reversible.**

### Schema

```sql
CREATE TABLE kodik_content_enrichment (
    content_id      BIGINT       NOT NULL,
    source          VARCHAR(32)  NOT NULL,    -- 'SHIKIMORI', 'KINOPOISK', 'MAL'
    fetched_at      DATETIME     NOT NULL,
    raw_payload     JSON         NOT NULL,    -- full provider response, for re-derivation if our extraction logic changes
    posters         JSON         NULL,        -- {"small": "...", "medium": "...", "large": "..."}
    title_native    VARCHAR(512) NULL,
    title_english   VARCHAR(512) NULL,
    synopsis        TEXT         NULL,
    score           DECIMAL(3,1) NULL,        -- 0.0 - 10.0
    score_count     INT          NULL,
    studios         JSON         NULL,        -- ["MAPPA", "Wit Studio"]
    aired_episodes  JSON         NULL,        -- [{episode: 1, aired_at: "2023-04-05"}, ...]
    PRIMARY KEY (content_id, source),
    KEY idx_fetched_at (fetched_at),
    CONSTRAINT fk_enrichment_content FOREIGN KEY (content_id) REFERENCES kodik_content (id) ON DELETE CASCADE
);
```

A content row can have up to 3 enrichment rows (one per source). Read-side queries `LEFT JOIN` the enrichment rows and prefer them in declared order: Shikimori for anime, Kinopoisk for non-anime, MAL for anime when Shikimori is missing.

### Pipeline

```
@Scheduled(cron = "0 0 4 * * *")    // daily at 04:00, low-traffic window
EnrichmentScheduler →
  for each kodik_content row missing or stale enrichment:
    classify(content) → SHIKIMORI | KINOPOISK | MAL  (based on type + presence of external_ids)
    EnrichmentService.fetch(content, source) →
      <source>Client.fetchMetadata(...) →
      upsert kodik_content_enrichment row
```

Stale = `fetched_at < now - 30 days` (configurable). External catalogues drift slowly; daily refresh of metadata is overkill.

### Configuration gates

- `orinuno.enrichment.enabled` (default `false`) — opt-in.
- `orinuno.enrichment.shikimori.enabled` / `.rate-limit` — Shikimori-specific.
- `orinuno.enrichment.kinopoisk.api-key` / `.enabled` — Kinopoisk requires key.
- `orinuno.enrichment.mal.enabled` / `.rate-limit` — MAL via Jikan.
- `orinuno.enrichment.refresh-after-days` (default 30).

### What we DO NOT enrich

- **Episode-level video URLs** — those are the provider decoders' job (Kodik / Aniboom / Sibnet / JutSu). Enrichment is metadata-only.
- **User watch history / "is this on my list?"** — orinuno is not a user-facing tracker.

## Consequences

### Positive

- `kodik_content` stays untouched — zero risk of corrupting data we already have.
- Re-derivation of extracted fields is cheap because we keep `raw_payload`. If we change our "what's a synopsis" rule, we re-run extraction without re-hitting upstream.
- Consumers that don't care about enrichment pay zero cost.

### Negative

- A new daily scheduled job that hits external APIs. Operators need to know about it for capacity planning.
- 3 different upstream APIs to keep up with — each can change shape independently.

### Neutral

- The enrichment pipeline is independent of ADR 0005 — it doesn't depend on the multi-provider schema and won't be affected by it.

## Blocked on

- Nothing structural. Lower priority than the multi-provider work because it doesn't affect the core "decode + serve a video URL" loop.

## Implementation tracker

| Step | Status | Notes |
|---|---|---|
| `kodik_content_enrichment` Liquibase changeset | Pending | |
| `ShikimoriClient.fetchMetadata` + tests | Pending | |
| `KinopoiskClient.fetchMetadata` + tests | Pending | needs API key wiring |
| `MalClient.fetchMetadata` + tests | Pending | via Jikan |
| `EnrichmentScheduler` + `EnrichmentService` | Pending | |
| Read-side join in `/api/v1/content/{id}` | Pending | additive |
