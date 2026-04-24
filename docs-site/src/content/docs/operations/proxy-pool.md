---
title: Proxy Pool
description: Round-robin proxy rotation with failure tracking and automatic direct fallback for decoder and CDN calls.
---

Orinuno can route every outbound call that talks to Kodik through a rotating
proxy pool. The pool is stored in the `kodik_proxy` table and driven by
`ProxyProviderService` + `ProxyWebClientService`.

## Enabling

```yaml
orinuno:
  proxy:
    enabled: true
    rotation-strategy: round-robin
```

Proxies must exist as rows in `kodik_proxy`. A minimal manual seed:

```sql
INSERT INTO kodik_proxy (host, port, username, password, proxy_type, status)
VALUES ('10.0.0.1', 8080, NULL, NULL, 'HTTP', 'ACTIVE');
```

## Rotation and fallback

- `ProxyProviderService` picks the next `ACTIVE` proxy in round-robin order.
- The proxy is applied to `WebClient` via an `HttpClient` with Netty's
  `HttpClient.create().proxy(...)`.
- On failure, `ProxyWebClientService.executeWithProxyFallback(...)` retries
  the same request without a proxy and increments `fail_count`.
- If `fail_count` crosses an internal threshold, the proxy is marked
  `FAILED` and skipped from rotation until an operator flips it back to
  `ACTIVE`.

## What uses the pool

| Caller | Purpose |
| --- | --- |
| `KodikVideoDecoderService` | Iframe fetch, player JS fetch, video-info POST |
| `HlsManifestService` | m3u8 fetch |
| `PlaywrightVideoFetcher` | Launches Chromium with proxy settings when enabled |

Kodik API (`kodik-api.com`) calls do **not** go through the proxy pool —
they use a dedicated WebClient with the user's token, which Kodik expects
from a stable IP.

## Operational notes

- The pool is eventually consistent. A `FAILED` proxy stays failed until
  you manually reset it; a future automated recovery check is tracked in
  the backlog.
- Proxy credentials are stored in plaintext in MySQL. Use network-level
  protection (private VPC, encrypted at rest) rather than relying on the
  application layer.

## Related

- [Video decoding](/orinuno/architecture/video-decoding/)
- [Monitoring](/orinuno/operations/monitoring/)
