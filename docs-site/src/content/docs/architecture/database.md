---
title: Database
description: MySQL schema — three tables, Liquibase migrations, composite upserts with COALESCE, and MyBatis XML mappers.
---

Orinuno uses MySQL 8 with Liquibase-managed migrations and MyBatis XML
mappers. The schema is small on purpose: three tables, all InnoDB, all
`utf8mb4_unicode_ci`.

## Entity relationships

```mermaid
erDiagram
    kodik_content {
        BIGINT id PK
        VARCHAR kodik_id
        VARCHAR type "anime, serial, movie..."
        VARCHAR title
        VARCHAR title_orig
        VARCHAR other_title
        INT year
        VARCHAR kinopoisk_id UK
        VARCHAR imdb_id
        VARCHAR shikimori_id
        VARCHAR worldart_link
        TEXT screenshots "JSON array"
        BOOLEAN camrip
        BOOLEAN lgbt
        INT last_season
        INT last_episode
        INT episodes_count
        VARCHAR quality
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    kodik_episode_variant {
        BIGINT id PK
        BIGINT content_id FK
        INT season_number
        INT episode_number
        INT translation_id
        VARCHAR translation_title
        VARCHAR translation_type "voice, subtitles"
        VARCHAR quality
        VARCHAR kodik_link "iframe URL"
        VARCHAR mp4_link "decoded CDN URL"
        DATETIME mp4_link_decoded_at "TTL tracking"
        VARCHAR local_filepath "downloaded .mp4 path"
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    kodik_proxy {
        BIGINT id PK
        VARCHAR host
        INT port
        VARCHAR username
        VARCHAR password
        ENUM proxy_type "HTTP, SOCKS5"
        ENUM status "ACTIVE, DISABLED, FAILED"
        DATETIME last_used_at
        INT fail_count
        TIMESTAMP created_at
        TIMESTAMP updated_at
    }

    kodik_content ||--o{ kodik_episode_variant : "has many"
```

## Tables

| Table | Purpose | Unique key |
| --- | --- | --- |
| `kodik_content` | Content metadata, one row per work | `kinopoisk_id` |
| `kodik_episode_variant` | Per-episode, per-translation variants with decoded mp4 links, TTL tracking, and local file paths | `(content_id, season_number, episode_number, translation_id)` |
| `kodik_proxy` | Proxy pool for rotation | `(host, port)` |

## Critical conventions

- **`COALESCE` on upsert.** When upserting `kodik_episode_variant`, the SQL
  uses `COALESCE(VALUES(mp4_link), mp4_link)`. This preserves a valid
  decoded link if a fresh API response happens to come without one.
- **`mp4_link_decoded_at`.** Every `UPDATE` of `mp4_link` sets this column
  to `NOW()`. The TTL refresh job uses it to find expired links.
- **Whitelisted `sortBy` and `order`.** The content list endpoint allows
  sorting by a fixed set of columns. The MyBatis XML uses `${...}`
  interpolation for those two fields only, and the controller validates the
  incoming values against a hard-coded whitelist before passing them in.

## Migrations

- Path: `src/main/resources/com/orinuno/db/changelog/scripts/`
- File naming: `YYYYMMDDHHMMSS_description.sql`
- Each file starts with `--liquibase formatted sql` and a
  `--changeset orinuno:YYYYMMDDHHMMSS` line.
- Every new migration must be registered in `liquibase-changelog.yaml`.

## Related

- [Kodik API flow](/orinuno/architecture/kodik-api-flow/)
- [Operations → TTL refresh](/orinuno/operations/ttl-refresh/)
