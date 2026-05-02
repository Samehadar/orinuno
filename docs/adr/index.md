# Architecture Decision Records

This directory holds the architectural decision records (ADRs) for orinuno. Each ADR captures a single durable decision: the context, the decision itself, and the consequences. ADRs are append-only — when a decision is superseded, write a new ADR that links to and explains why the old one no longer applies.

## Index

| # | Title | Status | Area |
|---|---|---|---|
| [0001](0001-kodik-sdk-extraction.md) | Kodik SDK extraction into `kodik-sdk-drift` module | Accepted | architecture |
| [0002](0002-kodik-http-method-policy.md) | Kodik HTTP method policy (POST is canonical, GET is router-accepted but discouraged) | Accepted | api |
| [0003](0003-kodik-decoder-post-body-format.md) | Kodik decoder POST body format (form-urlencoded, never JSON) | Accepted | decoder |
| [0004](0004-kodik-decoder-quality-strategy.md) | Decoder quality strategy: max numeric, ignore `default`, no 1080p rewrite | Accepted | decoder |
| [0005](0005-multi-provider-source-video-split.md) | Split `kodik_episode_variant` into `episode_source` + `episode_video` for multi-provider | Accepted (deferred) | schema |
| [0006](0006-sibnet-and-aniboom-providers.md) | Sibnet (PLAYER-3) and Aniboom (PLAYER-2) integration approaches | Accepted (deferred) | providers |
| [0007](0007-player5-shikimori-metadata-not-source.md) | PLAYER-5 (Shikimori): metadata index, NOT a video source | Accepted (deferred) | discovery |
| [0008](0008-ap7-multi-source-orchestration.md) | AP-7: Multi-source orchestration / "best URL for episode X" | Accepted (deferred) | ranking |
| [0009](0009-player4-jutsu-decoder.md) | PLAYER-4 (JutSu): direct-MP4 decoder, premium-tier blocked | Accepted (deferred) | providers |
| [0010](0010-meta1-metadata-enrichment.md) | META-1: metadata enrichment from Shikimori + Kinopoisk + MyAnimeList | Accepted (deferred) | enrichment |
| [0011](0011-kb1-knowledge-base-runbook.md) | KB-1: knowledge base / operator runbook | Accepted (phased) | docs |

## Status legend

- **Accepted** — decision is in force and reflected in the running code.
- **Accepted (deferred)** — decision is pinned but the implementation is parked behind another piece of work; see the ADR's "Blocked on" section.
- **Accepted (phased)** — implementation is incremental; see the ADR's tracker.
- **Superseded by ADR-NNNN** — historical; refer to the linked successor.

## Adding a new ADR

1. Pick the next number in sequence (don't reuse).
2. Use the template from ADR 0004 or 0005 — same five sections (Context / Decision / Consequences / Blocked on / Tracker).
3. Update this index in the same commit.
4. Cross-link from `docs/quirks-and-hacks.md` if the ADR codifies an existing quirk.
