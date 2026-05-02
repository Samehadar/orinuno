# Runbook: Decoder returns 0 qualities for a Kodik link

## Symptom

- `/api/v1/parse/decode` succeeds but `mp4_link` stays NULL.
- `kodik_episode_variant.decode_method = NULL` for newly-fetched rows.
- Metric `orinuno_decoder_outcomes{outcome="EMPTY_LINKS"}` climbs.
- Logs: `❌ Failed to decode variant id=… after N retries` repeated for the same content.

## Detect

```bash
mysql> SELECT id, kodik_link, mp4_link, decode_method, mp4_link_decoded_at
       FROM kodik_episode_variant
       WHERE mp4_link IS NULL
         AND kodik_link IS NOT NULL
       ORDER BY id DESC LIMIT 20;
```

```bash
curl -s http://orinuno/actuator/prometheus | grep -E "orinuno_decoder_(outcomes|method)"
```

If `decoder_method_total{method="REGEX",outcome="EMPTY"}` is non-zero AND
`decoder_method_total{method="SNIFF",outcome="SUCCESS"}` is zero, the regex broke and the sniff
fallback is either disabled or also failing.

## Mitigate

1. **Enable Playwright sniff fallback** if disabled:
   ```yaml
   orinuno:
     decoder:
       sniff-fallback-enabled: true
   ```
2. Restart the app so `KodikDecodeOrchestrator` picks up the new flag.
3. Manually re-decode the affected variants:
   ```bash
   curl -X POST http://orinuno/api/v1/parse/decode/variant/<id>
   ```

## Root-cause

- **Regex break** — open `KodikVideoDecoderService.PLAYER_JS_PATTERN`. Compare against what the
  iframe currently serves: `curl -s -L "https://kodik.cc/seria/<id>/<hash>" | grep -oE 'app\.[a-z]+\.[A-Za-z0-9]+\.js'`.
- **Quality strategy regression** — see `docs/quirks-and-hacks.md` (DECODE-1+7.4). The decoder
  intentionally drops the non-numeric `default` key. If Kodik renamed everything, the strategy
  needs a new fallback bucket.
- **Geo block** — see `provider-cdn-block.md`. CDN URLs may be reachable but return dummy
  content from non-CIS egress.

## Prevent

- Add the new player JS shape to `KodikVideoDecoderRegexTest` immediately.
- Bump `decoder_method_total{method="REGEX",outcome="EMPTY"}` into a Prometheus alert (warning at
  >5/min, page at >50/min).
- Make sure `sniff-fallback-enabled: true` is the production default.
