# ADR 0008 — AP-7: Multi-source orchestration / "best URL for episode X"

- **Status**: Accepted (implementation deferred — blocked on [ADR 0005](0005-multi-provider-source-video-split.md), [ADR 0006](0006-sibnet-and-aniboom-providers.md))
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → IDEA-AP-1..4, [ADR 0005](0005-multi-provider-source-video-split.md), [ADR 0006](0006-sibnet-and-aniboom-providers.md), [ADR 0007](0007-player5-shikimori-metadata-not-source.md), reference: `AnimeParsers/src/anime_parsers_ru/multi_searcher.py`

## Context

Once orinuno can ingest Kodik + Aniboom + Sibnet + JutSu (via ADR 0005 + ADR 0006 + ADR 0009), the same episode of the same anime will commonly have **2–4 different `episode_source` rows from different providers**. A consumer that wants to embed or stream the episode is now faced with a question: **which one should they pick?**

The naive answer is "the first one in the list", but real-world preferences are nuanced:

- **Kodik** is the highest-quality default for Russian-dub anime — has the most translations + most stable CDN.
- **Aniboom** has cleaner masters (DASH + HLS) but stricter geo-fencing — fails for a non-RU client.
- **Sibnet** has direct MP4 (no HLS overhead) but only ~50% of Kodik's anime catalogue.
- **JutSu** has the best subtitled-anime coverage but no decoder support for paid premium tiers.

Different consumers care about different things:

- A streaming proxy wants "the URL with the lowest expected latency from the consumer's geo".
- An embed consumer wants "the URL whose iframe doesn't fall back to ads on the consumer's region".
- A downloader wants "the URL with the highest available quality, even at the cost of slow download".
- A health monitor wants "all four URLs, even broken ones, so the operator can SSH in and triage".

AnimeParsers' `multi_searcher.py` solves this by collecting all sources for a given title and exposing them as a flat list — the consumer picks. We need something similar **but better integrated**: orinuno should know about freshness (decoded URLs have TTLs), failure history (a URL that 503'd 3× in the last hour is unlikely to work now), and geo affinity (operator's `X-Geo` hint matches one provider's geo strength).

## Decision

**Introduce a `MultiSourceRanker` service that returns a ranked list of `episode_video` rows for a given (content, season, episode) tuple. Ranking is deterministic, parameterizable, and exposed via REST so consumers can both ask "what's the best URL?" and "show me your decision matrix".**

### API shape

```
GET /api/v1/episodes/{contentId}/{season}/{episode}/sources
  ?prefer=quality|latency|reliability   (default: reliability)
  ?geo=RU|KZ|BY|...                     (default: omit; ranker uses provider defaults)
  ?translator_id=<kodik-translation-id> (default: omit; mix translators)

Response:
{
  "episode": { "content_id": ..., "season": 1, "episode": 5 },
  "ranked": [
    {
      "rank": 1,
      "provider": "KODIK",
      "translator": "AniLibria",
      "video_url": "https://...m3u8",
      "video_format": "application/x-mpegURL",
      "decoded_at": "2026-05-02T07:30:00Z",
      "ttl_seconds": 21600,
      "reliability_score": 0.95,
      "geo_affinity": "CIS",
      "decision_factors": [...]
    },
    {
      "rank": 2,
      "provider": "ANIBOOM",
      ...
    }
  ]
}
```

### Ranking inputs

The ranker scores each candidate `episode_video` row on three axes (each [0..1]):

1. **Freshness** — `1.0` when `decoded_at` is within `ttl_seconds`, decays linearly past TTL.
2. **Reliability** — derived from a rolling window of decoder + streaming outcomes per (provider, geo): `success / (success + failure)`. New providers start at `0.5` (neutral).
3. **Geo affinity** — table-driven multiplier per (provider, geo). Kodik has high CIS affinity; Aniboom has high RU affinity but low CIS-non-RU; Sibnet has high RU but middling other CIS; JutSu has high "any russian-speaking" affinity. Default values from operator-supplied YAML.

The `prefer` query param then weights the three axes:

| `prefer` | freshness | reliability | geo affinity |
|---|---|---|---|
| `reliability` (default) | 0.4 | 0.5 | 0.1 |
| `quality` | 0.2 | 0.2 | 0.6 (geo defines what's available; quality is implicit) |
| `latency` | 0.6 | 0.3 | 0.1 |

The final rank is `freshness × w_f + reliability × w_r + geo_affinity × w_g`.

### Decision factors transparency

Every ranked entry's `decision_factors` array carries the per-axis raw inputs + the multiplier weights so consumers can debug why entry X ranked above entry Y. This is the same pattern as the schema-drift health endpoint — observability beats guesswork.

## Consequences

### Positive

- Single endpoint that consumers can integrate against; no need to know provider quirks.
- Ranking is data-driven (rolling success window) so it adapts to provider degradation without code changes.
- The `prefer` knob lets consumers express intent without over-fitting the ranker.

### Negative

- Reliability tracking requires a new outbox-style table (`episode_video_outcome`) that observes every decode + stream attempt. Non-trivial schema work.
- Geo-affinity defaults are a judgment call. Operators will want to override per environment.

### Neutral

- This is purely a **read-side** feature. No changes to the decode pipeline, the dump pipeline, or the parse-request flow.

## Blocked on

- **ADR 0005 Phases A–C** — `episode_video` must exist before we can rank rows in it.
- **PLAYER-3 (Sibnet, ADR 0006) ships first** — the ranker becomes interesting only when `episode_source` has more than one provider for a given episode. With Kodik-only data the ranker is trivially "the only row".
- **Outcome tracking infrastructure** (a new `episode_video_outcome` table + outbox emitter on every decode + stream attempt). Not yet specced; this ADR will be split if the outcome tracker grows complex.

## Implementation tracker

| Step | Status | Notes |
|---|---|---|
| ADR 0005 Phases A–C | Pending | blocking |
| ADR 0006 Sibnet decoder shipped | Pending | first multi-provider data source |
| `episode_video_outcome` table + emitter | Pending | reliability input |
| `MultiSourceRanker` service + endpoint | Pending | |
| Operator YAML for geo affinity overrides | Pending | optional, defaults work |
