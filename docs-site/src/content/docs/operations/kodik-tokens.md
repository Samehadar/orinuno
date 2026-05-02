---
title: Kodik Tokens
description: Multi-tier Kodik API token registry with per-function availability, error-driven demotion, scheduled revalidation, and a scrape-based legacy fallback.
---

Orinuno manages Kodik API tokens locally through `KodikTokenRegistry`, a thread-safe
4-tier pool stored in `data/kodik_tokens.json`. The tier model and `functions_availability`
schema are a 1:1 port of [AnimeParsers'](https://github.com/YaNesyTortiK/AnimeParsers)
`kdk_tokns/tokens.json` — so the JSON is portable between the two ecosystems — but orinuno
neither ships nor publishes any third-party token values. Operators seed the pool locally.

## Tiers

A token lives in **exactly one** tier at a time. The registry promotes and demotes entries
automatically based on probe outcomes and live API error responses.

| Tier       | Preference                                                                                              |
|------------|---------------------------------------------------------------------------------------------------------|
| `stable`   | First pick. Full-scope tokens observed to survive days or weeks.                                        |
| `unstable` | Second pick. Full-scope tokens that rotate more often.                                                  |
| `legacy`   | Last resort. Partial-scope (`get_info` / `get_link` / `get_m3u8_playlist_link`).                        |
| `dead`     | Retired. Preserved for audit so the registry doesn't resurrect a token Kodik already rejected.          |

## Functions matrix

Each entry carries `functions_availability` — a snake-case map recording the last known
Kodik verdict per function. Keys match AnimeParsers' enum verbatim:

- `base_search`, `base_search_by_id`, `search`, `search_by_id`
- `get_list`
- `get_info`
- `get_link`, `get_m3u8_playlist_link`

`KodikApiClient` maps every outbound call to one of these keys (e.g. `POST /search` with a
`shikimori_id` → `base_search_by_id`; `POST /list` → `get_list`). On each call the registry
walks `stable → unstable → legacy` and returns the first token that is not explicitly
`false` for the requested function.

## Lifecycle

1. **Boot.** `data/kodik_tokens.json` is loaded (or bootstrapped if missing):
   - `KODIK_TOKEN` non-blank → seeded into `stable`.
   - Otherwise, if `orinuno.kodik.auto-discovery-enabled=true`, scrape
     `https://kodik-add.com/add-players.min.js?v=2` and seed the extracted token into
     `legacy`.
   - Otherwise, empty registry — API calls fail fast with `NoWorkingTokenException`.
2. **Startup validation.** A daemon thread probes three variants (`POST /search` by title,
   `POST /search` by `shikimori_id`, `POST /list`) with a 2-second gap, then updates
   `functions_availability` and `last_checked` per entry. Tokens whose every function
   returns false are demoted to `dead`.
3. **Scheduled revalidation.** Default every 6 hours
   (`orinuno.kodik.validation-interval-minutes`).
4. **Error-driven demotion.** Every response body is inspected for
   `{"error": "Отсутствует или неверный токен"}`. On match the registry flips the
   corresponding function flag to `false`, persists, and `KodikApiClient` retries with the
   next eligible token (up to `orinuno.kodik.token-failover-max-attempts`).

## Configuration

```yaml
orinuno:
  kodik:
    token: ${KODIK_TOKEN:}
    token-file: ${KODIK_TOKEN_FILE:./data/kodik_tokens.json}
    validation-interval-minutes: ${KODIK_TOKEN_VALIDATION_INTERVAL_MINUTES:360}
    auto-discovery-enabled: ${KODIK_TOKEN_AUTO_DISCOVERY_ENABLED:true}
    bootstrap-from-env: ${KODIK_TOKEN_BOOTSTRAP_FROM_ENV:true}
    token-failover-max-attempts: ${KODIK_TOKEN_FAILOVER_MAX_ATTEMPTS:3}
    validate-on-startup: ${KODIK_TOKEN_VALIDATE_ON_STARTUP:true}
```

## File schema

```jsonc
{
  "stable": [
    {
      "value": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
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
      "note": "personal operator token"
    }
  ],
  "unstable": [],
  "legacy": [],
  "dead": []
}
```

The file is gitignored. `data/kodik_tokens.example.json` is committed as a schema reference.
On POSIX filesystems the registry chmods the file to `600` on every write.

## Hand-editing

```bash
docker compose stop app
vim data/kodik_tokens.json
docker compose start app       # startup validation will rewrite availability flags
curl http://localhost:8085/api/v1/health/tokens
```

## Observability

- `GET /api/v1/health/tokens` returns masked entries (`value` replaced with
  `<first-4-chars>…(<length>ch)`), `lastChecked`, `note`, and per-function flags.
- Micrometer gauge `kodik_tokens_count{tier="stable|unstable|legacy|dead"}` — exposed via
  `GET /actuator/prometheus` on the management port.
- Every promotion/demotion is logged at `WARN` with the masked token prefix.

## Security

- The registry file lives only on local disk, never in git.
- Log output masks tokens (`prefix…(length)`) — raw values never leave the JVM.
- The `/health/tokens` endpoint masks values identically.
- Concurrent traffic is serialised by a `ReentrantReadWriteLock`; writers use
  `.tmp + ATOMIC_MOVE` to keep the file consistent across crashes.

## Credit

The tier taxonomy, `functions_availability` schema, and the `kodik-add.com/add-players.min.js`
fallback are imports from [AnimeParsers][ap-repo] (AGPL-3.0). orinuno re-implements the
behaviour in Java/Reactor and does not re-publish AnimeParsers' bundled token list.

[ap-repo]: https://github.com/YaNesyTortiK/AnimeParsers
