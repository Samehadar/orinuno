# ADR 0007 — PLAYER-5 (Shikimori): metadata index, NOT a video source

- **Status**: Accepted (implementation deferred — blocked on [ADR 0005](0005-multi-provider-source-video-split.md))
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → IDEA-AP-3, [ADR 0005](0005-multi-provider-source-video-split.md), [ADR 0006](0006-sibnet-and-aniboom-providers.md), reference: `AnimeParsers/src/anime_parsers_ru/parser_shikimori_async.py`

## Context

Looking at the AnimeParsers reference, `parser_shikimori_async` exposes Shikimori as a "parser" alongside Kodik / Aniboom / Sibnet / JutSu. This naming is misleading — it suggests Shikimori is a video source you can decode and stream. It is not.

What Shikimori actually is:

- **A metadata index** for anime / manga / ranobe (titles, genres, posters, descriptions, scores, episode counts, airing schedules, screenshots).
- **A user-facing tracker** (lists of "watched", "watching", "planned" per user).
- **A discovery surface** — given a free-form title, Shikimori returns the canonical record + a list of **third-party players** (Kodik, Aniboom, Sibnet) that host video for it.

Shikimori does NOT host or decode video. The "player list" it returns is just metadata: links to Kodik / Aniboom / Sibnet iframes that we'd then have to decode via the existing or planned provider pipelines (ADR 0005, ADR 0006).

So the question is: **what role does Shikimori play in orinuno?**

Two distinct use cases that look the same on the surface but have very different implications:

1. **Discovery** (Phase D2 of ADR 0005): "I want to find every anime in Shikimori's catalogue and ingest its Kodik/Aniboom URLs into orinuno." — Shikimori-as-discovery feeds the existing parser pipeline.
2. **Metadata enrichment** (META-1, see [ADR 0010](0010-meta1-metadata-enrichment.md)): "I have a `kodik_content` row; I want to fill in posters, screenshots, native-language title from Shikimori." — Shikimori-as-enricher feeds the content table.

These are independent. We could ship either without the other.

## Decision

**Treat PLAYER-5 (Shikimori) strictly as a discovery + metadata source. Do not introduce a `provider='SHIKIMORI'` row in `episode_source`. Phase the implementation behind PLAYER-1 / ADR 0005 anyway, because the discovery output (a list of Kodik/Aniboom/Sibnet URLs) needs the multi-provider schema to land cleanly.**

Concretely:

1. Introduce `service/discovery/shikimori/ShikimoriClient.java` — wraps the Shikimori REST + GraphQL APIs. Read-only, rate-limited per Shikimori's published limits (5 req/sec).
2. Introduce `service/discovery/shikimori/ShikimoriDiscoveryService.java` — given a Shikimori anime ID (or a discovery query), fetches the player list and dispatches one parse-request per (provider, URL) tuple to the existing `ParseRequestService`.
3. The metadata enrichment side of Shikimori belongs to META-1 / ADR 0010 — not this ADR.

### What the Shikimori discovery flow looks like

```
operator (or a scheduled job) →
  POST /api/v1/discovery/shikimori { "shikimori_id": "12345" } →
  ShikimoriDiscoveryService.discover(12345) →
    ShikimoriClient.fetchPlayerList(12345) →
      [ {provider: "KODIK", url: "//kodikplayer.com/..."},
        {provider: "ANIBOOM", url: "https://aniboom.one/embed/..."} ] →
    for each (provider, url):
      ParseRequestService.submit(...) → episode_source row →
        decode pipeline (ADR 0005 Phase D / D2)
```

Net effect: Shikimori IDs become a **discovery primitive** that fans out to the existing per-provider decoders. The `episode_source.provider` column never becomes `'SHIKIMORI'`.

## Consequences

### Positive

- We don't pollute the schema with a "fake" provider that has no video URL of its own.
- Discovery scales naturally — a one-time backfill of "all Shikimori anime" produces a queue of parse-requests that the existing worker drains.
- META-1 (metadata enrichment from Shikimori) stays cleanly separable from the discovery flow.

### Negative

- AnimeParsers users who expect a "Shikimori parser" symmetric with the others will be surprised. Document the asymmetry up-front in the package Javadoc and in the public REST docs.

### Neutral

- The Shikimori rate limit (5 req/sec) is generous compared to our parse-request throughput, so backpressure is not a concern.

## Blocked on

- **ADR 0005 Phase A–C** — the multi-provider schema must exist before we have a place to write the discovered (provider, URL) tuples.
- **CIS-region egress** — Shikimori's API responds normally from any IP, but the player-list URLs are Kodik / Aniboom / Sibnet links that themselves need CIS egress to decode (see `docs/quirks-and-hacks.md` 2026-05-02 KZ VPN entry).

## Implementation tracker

| Step | Status | Notes |
|---|---|---|
| ADR 0005 Phases A–C | Pending | blocking |
| `ShikimoriClient` skeleton + tests | Pending | |
| `ShikimoriDiscoveryService` + REST endpoint | Pending | |
| Backfill `CommandLineRunner` for "all anime" | Pending | gated; opt-in like ADR 0005 backfill |
