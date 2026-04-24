# Kodik Tokens — Operator Guide

> Inspired by AnimeParsers' [`kdk_tokns/TOKENS.md`][ap-tokens-md] but adapted for a server
> that manages its token pool **locally**. The registry file is never published to git and
> is not obfuscated (readable JSON, POSIX-chmoded to `600`).

## TL;DR

- Source of truth: `data/kodik_tokens.json` (gitignored).
- Schema reference: `data/kodik_tokens.example.json` (committed).
- Managed by: `com.orinuno.token.KodikTokenRegistry` (in-process, atomic writes).
- First boot: auto-seeds from `KODIK_TOKEN` env, then optionally scrapes a legacy token.

## Tiers

Mirroring AnimeParsers 1:1 so the JSON shape is portable. A token lives in **exactly one**
tier at a time; the registry promotes/demotes entries automatically as Kodik accepts or
rejects them.

| Tier       | Description                                                                                                  |
|------------|--------------------------------------------------------------------------------------------------------------|
| `stable`   | Full-scope token; observed to survive across days/weeks. Preferred first.                                    |
| `unstable` | Full-scope token that rotates more often. Used when no `stable` entry exists or all `stable` got rejected.   |
| `legacy`   | Partial-scope token — typically only `get_info` / `get_link` / `get_m3u8_playlist_link`. Last resort.        |
| `dead`     | Retired token. Kept for audit so the registry doesn't resurrect a value Kodik already rejected.              |

## Function scope

Each entry carries `functions_availability` — a map (snake-case keys) recording the last
known verdict per function. Keys match AnimeParsers' enum:

| Key                         | Purpose in orinuno                                           |
|-----------------------------|--------------------------------------------------------------|
| `base_search`               | `POST /search` by title                                      |
| `base_search_by_id`         | `POST /search` by external id (shikimori / kinopoisk / etc.) |
| `get_list`                  | `POST /list`                                                 |
| `search` / `search_by_id`   | Convenience wrappers over the two above                      |
| `get_info`                  | Reference endpoints (`/translations`, `/genres`, …)          |
| `get_link`                  | Per-episode link resolution (uses `/search`)                 |
| `get_m3u8_playlist_link`    | HLS manifest discovery (uses `/search`)                      |

Keys not listed here are preserved verbatim when the registry rewrites the file — forward
compatibility with future AnimeParsers additions.

## Lifecycle

1. **Boot.** `KodikTokenRegistry` loads `data/kodik_tokens.json`. If the file is missing:
   - if `orinuno.kodik.token` (env `KODIK_TOKEN`) is non-blank → seed it into `stable`;
   - else if `orinuno.kodik.auto-discovery-enabled=true` → scrape
     `https://kodik-add.com/add-players.min.js?v=2` for a legacy token and seed it into
     `legacy`;
   - else → empty registry, API calls fail fast with `NoWorkingTokenException`.
2. **Startup validation** (`validate-on-startup=true` by default). A daemon thread probes
   three `/search` / `/list` variants against every live token and refreshes the
   `functions_availability` map. Tokens whose every function returns false are demoted to
   `dead`.
3. **Scheduled revalidation.** `orinuno.kodik.validation-interval-minutes` (default 360 =
   6 hours). Same probes, 2-second spacing between calls.
4. **Error-driven demotion.** `KodikApiClient` inspects each Kodik response; if the body
   contains `{"error": "Отсутствует или неверный токен"}`, the current token is marked
   invalid for that function and the call is retried with the next eligible token (up to
   `token-failover-max-attempts`). When all functions die, the entry is moved to `dead`.

## File format

```jsonc
{
  "stable": [
    {
      "value": "4492ae176f94d3103750b9443139fdc5",
      "functions_availability": {
        "base_search": true,
        "base_search_by_id": true,
        "get_list": true,
        "search": true,
        "search_by_id": true,
        "get_info": true,
        "get_link": true,
        "get_m3u8_playlist_link": true
      },
      "last_checked": "2026-04-24T18:00:00Z",
      "note": "personal operator token — seeded from KODIK_TOKEN env"
    }
  ],
  "unstable": [ /* same shape */ ],
  "legacy":   [ /* same shape, frequently partial availability */ ],
  "dead":     [ /* audit trail, ignored by currentToken() */ ]
}
```

Fields:

| Field                    | Type           | Notes                                                       |
|--------------------------|----------------|-------------------------------------------------------------|
| `value`                  | `string`       | Raw 32-hex Kodik token.                                     |
| `functions_availability` | `object<bool>` | Per-function verdict. Unknown keys are preserved verbatim.  |
| `last_checked`           | `string` (ISO) | Timestamp of the last write (success or failure).           |
| `note`                   | `string?`      | Free-form operator comment. Never populated by the service. |

## Hand-editing workflow

```bash
# 1. Stop the service (don't edit while it's running — you'll race with writes).
docker compose stop app

# 2. Edit the file.
nvim data/kodik_tokens.json

# 3. Restart. Startup validation will rewrite flags based on probe results.
docker compose start app

# 4. Check status (no auth required).
curl http://localhost:8085/api/v1/health/tokens
```

## Observability

- `GET /api/v1/health/tokens` → masked summary (`lastChecked`, `note`,
  `functions_availability`; raw `value` is never emitted).
- Prometheus gauge `kodik_tokens_count{tier="stable|unstable|legacy|dead"}` — scrape via
  `/actuator/prometheus`.
- Logs: every promotion/demotion is `WARN` with the masked token prefix
  (first 4 chars + length), never the raw value.

## Security

- The registry file is chmoded to `600` on POSIX filesystems.
- The file is listed in `.gitignore` and docker-compose mounts it as a volume.
- The in-memory model is guarded by a `ReentrantReadWriteLock`; concurrent readers (API
  traffic) don't block each other and writers (probes / markInvalid) are serialised.

## Credit

The tier taxonomy, `functions_availability` schema, and the `add-players.min.js` fallback
are direct imports from [AnimeParsers][ap-repo] (AGPL-3.0). orinuno re-implements the
behaviour in Java/Reactor but does **not** re-publish AnimeParsers' token list — operators
seed their own pool.

[ap-repo]: https://github.com/YaNesyTortiK/AnimeParsers
[ap-tokens-md]: https://github.com/YaNesyTortiK/AnimeParsers/blob/main/src/anime_parsers_ru/kdk_tokns/TOKENS.md
