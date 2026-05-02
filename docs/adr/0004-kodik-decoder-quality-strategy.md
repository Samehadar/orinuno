# ADR 0004 — Decoder quality strategy: max numeric, ignore `default`, no 1080p rewrite

- **Status**: Accepted
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → DECODE-1, DECODE-7.4, [docs/quirks-and-hacks.md](../quirks-and-hacks.md), [docs/research/2026-05-02-api-and-decoder-probe.md](../research/2026-05-02-api-and-decoder-probe.md)

## Context

DECODE-1 in the backlog asked: should we expose a `preferred-quality` knob so that consumers can opt into 1080p / 2160p instead of always taking the max we find? DECODE-7.4 asked: should we treat the `default` field in the `/ftor` response as a hint and pick that quality instead of the max?

A live probe across 10 different titles on 2026-05-02 showed:

- **Every** observed `/ftor` response capped at 720p. Distribution: 5/10 had `{360, 480, 720}`, 4/10 had `{240, 360, 480, 720}`, 0/10 had any 1080p or 2160p key.
- The `default` field was **always** `360`. This is Kodik's bandwidth-conservative default for the embedded player, NOT the best quality available for that content.
- Sending a "forced 1080p" URL (replacing `720p` with `1080p` in the iframe URL — the trick used by some Telegram bots) returns the 720p file. There is no hidden 1080p source.

Existing implementation in [`ParserService.selectBestQuality()`](../../orinuno-app/src/main/java/com/orinuno/service/ParserService.java) already does the right thing:

```java
return videoLinks.entrySet().stream()
    .filter(e -> !e.getKey().startsWith("_"))
    .filter(e -> e.getValue() != null && e.getValue().startsWith("http"))
    .max((a, b) -> Integer.compare(Integer.parseInt(a.getKey()), Integer.parseInt(b.getKey())))
    .map(Map.Entry::getValue)
    .orElse(null);
```

— max numeric quality key, ignore `default`, ignore type field, ignore `_`-prefixed keys (in case Kodik adds private metadata like `_advert`).

## Decision

**Keep `selectBestQuality` as-is. Do not introduce a `preferred-quality` knob. Do not rewrite `720p` → `1080p` in URLs. Add observability so we know if the quality cap ever changes.**

Concretely:

1. `selectBestQuality` stays at "max numeric quality among URL-shaped values" — already correct.
2. **No `orinuno.decoder.preferred-quality` config setting** is added. Quality is one of the two columns we promise consumers (the other being `mp4_link`). Adding a knob would make us non-determ­inistic per deployment and complicate the export contract.
3. **No URL rewrite** for forced higher qualities. We leave the URL Kodik gave us, period.
4. **Add Prometheus counter** `orinuno.decoder.quality{quality}` — incremented in `ParserService.decodeForVariant()` after `pickBestQualityEntry` returns. This gives us a histogram of "max-quality buckets observed in the wild" so we can spot the day Kodik introduces 1080p (the day a `quality="1080"` bucket appears) without anyone asking us to add it. **Implemented in DECODE-6 commit** (see [`KodikDecoderMetrics`](../../orinuno-app/src/main/java/com/orinuno/service/metrics/KodikDecoderMetrics.java) — also exposes `orinuno.decoder.path` and `orinuno.decoder.shift` for visibility into short-circuit vs brute-force vs cached-shift hit rates).
5. The "rename `mp4_link` → `media_link`" idea is parked indefinitely. Renaming the column would break every downstream contract (export DTO, MyBatis XML, OpenAPI spec, Astro docs, parser-kodik consumer expectations, and the `mp4_link_decoded_at` companion column) for cosmetic reasons. The Javadoc on the column already says "Direct media URL — usually .m3u8 (HLS), historically .mp4". That is enough.

## Trade-offs

**Cost of NOT adding `preferred-quality`**: power users who want to optimize bandwidth can't ask us to skip 720p. No real demand has surfaced.

**Cost of adding `preferred-quality`**: changes our promise to consumers (we currently promise "best quality available"). Would also require persisting the chosen quality alongside the URL (TTL refresh would otherwise downgrade quality silently). High cost, low benefit.

**Cost of NOT rewriting URLs**: we are honest about Kodik's actual catalogue. Acceptable.

**Cost of NOT renaming column**: a minor DTO field name mismatch. Documented in the `KodikEpisodeVariant` Javadoc.

## Verification

- [`ParserServiceTest.selectBestQuality*`](../../orinuno-app/src/test/java/com/orinuno/service/ParserServiceTest.java) (existing 4 tests, lines ~115-160): assert max-numeric pick, sentinel-key skipping, null-on-empty, non-http defensive skip.
- [`KodikDecoderRawProbeTest.qualityDistributionProbe`](../../orinuno-app/src/test/java/com/orinuno/service/KodikDecoderRawProbeTest.java) (new in this commit) — when `KODIK_TOKEN` is set, runs the same 10-title quality probe used to draft this ADR, fails the build (loud `System.err` warning, not assertion) when a `1080`/`2160` bucket appears so we re-evaluate this ADR.
- The Prometheus counter is added in DECODE-6 (next phase).
