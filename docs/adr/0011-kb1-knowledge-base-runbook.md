# ADR 0011 — KB-1: knowledge base / operator runbook for orinuno

- **Status**: Accepted (implementation phased — start with the runbook directory + 5 highest-value runbooks)
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → IDEA-AP-3..6, [docs/quirks-and-hacks.md](../quirks-and-hacks.md), every other ADR (0001–0010)

## Context

orinuno has accumulated tribal knowledge faster than the docs can keep up. Today, when an operator hits a problem, they have to search across:

- `README.md` (high-level intro)
- `AGENTS.md` (AI-agent context)
- `BACKLOG.md` (1000+ lines, mostly Russian)
- `TECH_DEBT.md`
- `ARCHITECTURE.md` (Mermaid diagrams)
- `docs/quirks-and-hacks.md` (the "things that bit us" log)
- `docs/adr/` (10 ADRs and counting)
- `docs/research/` (one-shot probe writeups)
- `docs-site/` (the public Astro docs site)
- The 67+ live API stability tests
- `CLAUDE.md` and per-folder `AGENTS.md`

For the maintainers this is fine — they wrote it. For a new contributor or an on-call operator, it's a maze. **The first 30 minutes of an incident is spent finding which file has the answer.**

The 5 highest-frequency questions we've seen (rough order):

1. "Decoder is suddenly returning empty maps for everything — is Kodik's player JS broken or is it just me?"
2. "Why do some episodes have `mp4_link='true'`?"
3. "How do I rotate the Kodik token? What if I don't have one?"
4. "Why is `/api/v1/parse/requests` returning 429 to my service?"
5. "How do I run a one-off backfill / migration safely?"

Each has an answer scattered across 2–4 files today. The ADR / quirks-doc fragmentation is good for *recording* knowledge but bad for *finding* it.

## Decision

**Introduce `docs/runbooks/` — a flat directory of focused, action-oriented operator runbooks. Each runbook answers ONE recurring question and links out to the source material (ADRs, quirks doc, code) for deeper reading.**

### Format

Every runbook has the same 5-section layout (mirrors PagerDuty / Google SRE template):

1. **When this fires** — the symptom that brings an operator here.
2. **Quick check** (1–3 commands) — the smallest action that confirms or rejects the diagnosis.
3. **Mitigation** — what to do RIGHT NOW to stop the bleeding.
4. **Root-cause investigation** — the longer "why did this happen" path, with links to ADRs / quirks-doc / source.
5. **Prevention** — what work would prevent recurrence (links to BACKLOG items).

### Initial runbook set (5 to ship in the first KB-1 commit)

| File | Question it answers |
|---|---|
| `runbooks/decoder-emitting-empty-maps.md` | "Decoder broke — Kodik player JS rewrite, or my problem?" |
| `runbooks/mp4-link-equals-true.md` | "What is `mp4_link='true'` and how do I unblock the rows?" |
| `runbooks/kodik-token-rotation.md` | "How do I add / rotate / debug Kodik tokens?" |
| `runbooks/parse-requests-429.md` | "Why is `/api/v1/parse/requests` returning 429? How do I unblock my service?" |
| `runbooks/safe-backfill-procedure.md` | "How do I run a one-off backfill / Liquibase migration without breaking prod?" |

Each runbook should be **≤ 200 lines** so it stays scannable. Cross-linking to ADRs / quirks-doc / source files is mandatory; copy-pasting content is not (single source of truth lives in the originating doc).

### Index page

A `docs/runbooks/index.md` lists every runbook by title + 1-line summary. Operators land here from the README.

### Why a separate directory + format (vs. expanding `quirks-and-hacks.md`)

- `quirks-and-hacks.md` is a **history log** ("here's a weird thing we discovered"). It's chronologically ordered. Search-friendly but not action-friendly.
- A runbook is a **playbook for an incident**. It assumes the operator is mid-fire and needs the smallest possible delta from "broken" to "limping".
- The two formats serve different audiences. Forcing them into one file optimizes for neither.

## Consequences

### Positive

- New contributors and on-call operators get a flat, self-describing index of "things that have gone wrong before".
- Each runbook crystallizes the implicit knowledge that's already in chat / commit messages / quirks entries.
- Runbooks become a forcing function for documentation: "If this incident is hard to diagnose, write a runbook so the next person isn't stuck."

### Negative

- Documentation drift risk. A runbook that links to a moved file or a renamed property silently rots. Mitigation: the next ADR (0012, future) should set up a docs-link-checker in CI.
- Yet-another-doc-tree. The README needs to call out where the runbooks live (one-line update).

### Neutral

- KB-1 doesn't ship code. Pure documentation work. No tests, no migrations, no risk to production.

## Blocked on

- Nothing structural. The 5 initial runbooks can land as soon as someone has 1–2 hours per runbook.

## Implementation tracker

| Step | Status | Notes |
|---|---|---|
| `docs/runbooks/` directory + `index.md` | Pending | scaffold |
| `decoder-emitting-empty-maps.md` | Pending | highest priority — recurring incident |
| `mp4-link-equals-true.md` | Pending | |
| `kodik-token-rotation.md` | Pending | links to `data/TOKENS.md` |
| `parse-requests-429.md` | Pending | links to TD-2 + Phase 2 docs |
| `safe-backfill-procedure.md` | Pending | links to ADR 0005 backfill phase |
| README pointer to `docs/runbooks/` | Pending | one-line edit |
| (Future) docs-link-checker in CI | Deferred | separate ADR if needed |
