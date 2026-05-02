# Runbook: All Kodik tokens revoked / unusable

## Symptom

- Every `KodikApiClient` call fails with `KodikTokenException` /  `NoWorkingTokenException`.
- `/api/v1/health` reports `kodik.tokens.usable = 0`.
- 401/403 spike from `kodik-api.com` in `KodikDecoderMetrics`.

## Detect

```bash
curl -s http://orinuno/api/v1/health | jq '.kodik.tokens'
```

```bash
mysql> -- pull the on-disk registry snapshot
       cat /opt/orinuno/data/kodik_tokens.json | jq '.tokens[] | {value, tier, last_error}'
```

If every entry has `tier=UNSTABLE` and `last_error` references HTTP 401 or
`NoWorkingTokenException`, the upstream Kodik admin invalidated the tokens (most common reason).

## Mitigate

1. Pull the latest token list from the partner channel (Telegram chat / shared vault).
2. Update `data/kodik_tokens.json` (preserve the JSON structure — see `data/TOKENS.md`):
   ```json
   {
     "tokens": [
       {"value": "<new-token>", "tier": "STABLE", "functions_availability": ["search","list"]}
     ]
   }
   ```
3. Restart the app so `KodikTokenRegistry` re-hydrates.
4. Verify: `curl -s http://orinuno/api/v1/health | jq '.kodik.tokens.usable'` → ≥1.

## Root-cause

- Tokens are rate-limited per-account; if multiple instances of orinuno share the same token they
  trip per-IP limits. Confirm via `mysql> SELECT host FROM kodik_proxy WHERE used_by=…`.
- Kodik occasionally rotates its admin signing key — every old token dies at once. There's no
  notification; we learn from monitoring.

## Prevent

- Keep at least 2 tokens at `tier=STABLE` and 1 at `tier=UNSTABLE` (auto-promotes on first
  successful use).
- Wire an alert on `kodik.tokens.usable < 1` for >5 minutes.
- The auto-discovery fallback (scrapes `kodik-add.com/add-players.min.js`) is best-effort —
  don't depend on it as the only source.
