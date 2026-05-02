# Runbook: Provider CDN blocks production egress

## Symptom

- Sibnet/Aniboom/JutSu decoders return `*_FETCH_ERROR` or
  `*_GEO_BLOCKED` consistently from production.
- Kodik decoder returns valid links but `mp4_link` resolves to `403 Forbidden` /
  redirect-to-stub when consumers try to play it.
- Same URLs play fine from a developer laptop on KZ/RU/BY VPN.

## Detect

```bash
# Confirm IP geo as seen by the upstream
curl -s https://ifconfig.co/json | jq '.country, .country_iso, .city'

# Hit the failing CDN with the same headers we send
curl -I -H "Referer: https://video.sibnet.ru/" \
        -H "User-Agent: <stable-desktop-UA>" \
        "https://video.sibnet.ru/v/<id>/master.mp4"
```

A 403 with `cf-ray` header → Cloudflare WAF; with `Server: nginx` and an empty body → manual
geo-block.

## Mitigate

1. **Switch egress** — every production pod should be configured with a per-provider proxy under
   `orinuno.kodik.proxy.*`. Failover to the secondary CIS proxy:
   ```yaml
   orinuno:
     kodik:
       proxy:
         enabled: true
         host: <secondary-cis-proxy>
         port: 8080
   ```
2. Restart the pod that hit the block; restart-on-failure should re-pick the proxy.
3. **For Aniboom only**: the resolved HLS URL must be served WITH the original
   `Referer: https://animego.org/`. If consumers strip the Referer the CDN refuses. Check the
   downstream proxy's request headers in the access log.

## Root-cause

- Production rolled out from a non-CIS region (Cloudflare PoP outside the CIS allowlist).
- Provider rotated their hotlink-protection token (rare; affects all egress at once).
- For Cloudflare 403: bot-detection challenge — the `User-Agent` rotation may have picked a UA
  that's flagged. Pin a known-good UA via `RotatingUserAgentProvider.stableDesktop()` and
  redeploy.

## Prevent

- All decoders MUST set the provider-specific `Referer` header (already enforced in
  `SibnetDecoderService.REFERER`, `AniboomDecoderService.REFERER`).
- Healthcheck should `HEAD` one known-good URL per provider every 5 minutes. Alert on 3
  consecutive failures.
- Document the egress contract in `docs/quirks-and-hacks.md` — "Always serve with the same
  Referer used during decode".
