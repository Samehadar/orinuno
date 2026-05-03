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

## Not a CDN block: provider-specific failure modes

Some `*_FAILED` codes look like CDN blocks but mean something else. Don't waste time on the
mitigations above if you see one of these:

| Code | What it actually means | Where to go |
|---|---|---|
| `JUTSU_PREMIUM_REQUIRED` | The episode (or whole series) is gated behind `Jutsu+` paid subscription. The page came back fine — it just doesn't include real CDN URLs. | **First**, check `JUTSU_USERNAME` / `JUTSU_PASSWORD` env vars — if blank, the decoder is in anonymous mode by design and a `Jutsu+` account would unlock this episode. **If creds are set**, the decoder already retried after invalidating the session, so this is either a banned account or a series where the specific episode is gated even for `Jutsu+`. Try a different episode, rotate to a fresh account, or fall back to another provider via `GET /api/v1/sources/{contentId}/{season}/{episode}`. See also the "DLE auth + sticky cookies" entry in `docs/quirks-and-hacks.md`. |

## "JutSu URL works in backend but returns 403 when I open it in a browser"

Not a runbook entry, by design — it's how Yandex CDN signs URLs for jut.su. The decoded `r{N}.yandexwebcache.org/...` URL is signed against the session that fetched the episode page (backend's own cookie jar / IP). Any other session — your browser, another curl, an iframe in another tab — will get 403 instantly. See `docs/quirks-and-hacks.md` → "JutSu DLE auth + sticky cookies + 1 RPS hard cap" → "CDN URLs are session-bound".

**Operator playback path** (PROXY-1 — implemented): the demo UI's `▶ Play` button on each quality URL routes through `GET /api/v1/providers/jutsu/stream?url=…`. The proxy re-issues the request from inside backend's session and streams the response to the browser. If you need to verify the proxy itself, pull a small range straight from the proxy:

```bash
curl -s -o /dev/null -w 'HTTP %{http_code} | type=%{content_type} | bytes=%{size_download}\n' \
  -H 'Range: bytes=0-1023' \
  "http://localhost:8080/api/v1/providers/jutsu/stream?url=$(printf %s 'https://r270106.yandexwebcache.org/...mp4?...' | jq -sRr @uri)"
```

Expected: `HTTP 206 | type=video/mp4 | bytes=1024`.

**Operator raw-decoder verification** (no proxy): if you want to confirm the *decoder* still extracts the URL correctly without involving the proxy, curl the URL from the same machine running the backend (so the Yandex CDN sees the same outbound IP as backend's session) with `Referer: https://jut.su/` and a desktop UA. Anywhere else — your laptop, a CI worker, an iframe in another tab — will return 403 by design.

**Bypass attempts that do not work** (verified 2026-05-03):

- adding `Referer: https://jut.su/`
- adding `User-Agent: Mozilla/...` matching the one backend used
- adding the same set of `Sec-Ch-Ua-*` headers Chrome 147 sends
- copying backend's session cookies (`PHPSESSID`, `dle_user_id`, `dle_password`, `LB_member_sc`)
- waiting / not waiting (URL is not short-TTL — it's session-bound, not time-bound)

The Yandex CDN signs `(host, hash, hash2)` against the originating session/IP and that signature cannot be replayed from elsewhere. PROXY-1 (above) is the only supported path.
| `JUTSU_PLAYER_MISSING` | The page returned without the `<video>` block at all. Almost always bot detection — the request is missing a session cookie or the `User-Agent` is on a deny-list. | Pin a known-good UA, add a referer header, or warm up a session cookie. See `RotatingUserAgentProvider.stableDesktop()`. |
| `JUTSU_SOURCE_TAG_MISSING` | Player block present, but no `<source src="….mp4">` matched. | Schema drift — re-grep the response, update `JutsuDecoderService.SOURCE_TAG`. |
| `ANIBOOM_GEO_BLOCKED` | Aniboom CDN refused the resolved playlist URL because the request landed in a non-CIS PoP. | Real CDN block — apply the steps above. |
| `SIBNET_VIDEO_NOT_FOUND` | The video id is gone (uploader deleted, or the upload was rejected). | Re-discover via the original Kodik / Shikimori entry. |

For the JutSu codes specifically, see also `docs/quirks-and-hacks.md` → "JutSu premium gating leaks
`<source>` tags with placeholder URLs" for the underlying mechanic.
