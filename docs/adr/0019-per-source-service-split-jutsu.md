# ADR 0019 — Per-source service split: JutSu next

Status: **Accepted** — Phase 3 calendar gate dropped (pre-prod refactor mode, no prod traffic to measure stability against); Phase 4 implementation begins immediately.
Date: 2026-05-12
Supersedes: nothing.
Refines: ADR 0018 (extends the split pattern to the second source).
Companion of: ADR 0012 (`jutsu-sdk` extraction — prerequisite, already accepted).

## Context

ADR 0018 codified the per-source service split and shipped Kodik as the first cut. Phase 4 of that ADR was earmarked for jut.su but the actual jut.su-specific decisions, deviations from the Kodik recipe, and migration steps live here so future-us can implement Phase 4 from a single reference.

**Why now (defining the work, not starting it):** Phase 2 of ADR 0018 is code-complete; the validation gate (ADR 0018 Phase 3 — 14 days of prod traffic on `orinuno-source-kodik`) blocks execution but does not block planning. Pre-committing the jut.su shape lets us:

- catch SDK / contract drift now (jut.su's HTML drift detector is more aggressive than Kodik's JSON drift; the standalone deployable must keep its existing failure isolation guarantees intact);
- size the Phase 4 PR fan-out before the gate clears (live-fallback Playwright + circuit breaker + negative cache + sticky-session SDK don't all fit in one PR comfortably);
- pre-publish the `downstream jut.su consumer` / consumer cutover recipe so Kin (and any OSS consumer) can plan their config flips.

**Triggers fired (same four as ADR 0018, jut.su-specific notes):**

1. **Standalone product** — selling/integrating jut.su parser separately. jut.su has a smaller user base than Kodik but is the only AAA path for the ~20 simulcast titles per season that Kodik doesn't license, so OSS users frequently want jut.su without the Kodik stack.
2. **OSS ↔ corporate split** — community contributions on HTML drift / selectors land faster when the parser ships independently. jut.su's DOM is hand-curated; a single mid-season layout change today requires a consumer-internal release.
3. **Per-parser failure isolation** — jut.su's live-fallback path (Playwright/Chromium scrape) is the most fragile thing in `orinuno-app` today. Premium-tier 403s, slug 404s, sticky-session breakage, Chromium OOMs — none of these should be able to take down the Kodik path or the orinuno gateway.
4. **Preventive scaling** — same Aniboom/Sibnet/Shikimori expansion argument from ADR 0018.

## Decision

`orinuno-source-jutsu` ships as a standalone Spring Boot deployable, mirroring `orinuno-source-kodik`'s shape and inheriting every cross-cutting decision from ADR 0018. This ADR records only the jut.su-specific deltas.

### Target topology (delta vs ADR 0018)

```
                                          /api/v1/source-events/ready
                                          (jut.su SourceCatalogEvent stream)
                                                      ▲
                                                      │ poll
                                                      │
   demo UI / external consumers                       │
            │                                         │
            │ /api/v1/sources/jutsu/*                 │
            │ /api/v1/sources/jutsu/stream            │
            │ /api/v1/providers/jutsu/stream  (legacy)│
            ▼                                         │
   ┌────────────────┐  reverse-proxy   ┌──────────────┴────────────┐
   │    orinuno     │ ───────────────► │ orinuno-source-jutsu      │
   │   (gateway)    │                  │ (standalone, port 8091)   │
   │                │                  │  - jutsu-sdk + starter    │
   │                │                  │  - jutsu_* MySQL schema   │
   │                │                  │  - Playwright fallback    │
   └────────┬───────┘                  └──────────────┬────────────┘
            │                                         │
            │ /api/v1/catalog/*                       │ JIT URL decode
            ▼ (read-only DB)                          ▼ (per-request)
   ┌────────────────┐                          jut.su upstream
   │     meter      │
   │ + catalog DB   │
   └────────────────┘
```

### Source classification — already settled

jut.su's classification flipped to "HTML-drift parser" in ADR 0012 + ADR 0015. The full-browser-parity contract (catalog, search, anime info, episode meta, notice feed) is the durable surface; jut.su's DOM changes ~3–4 times a year and the SDK already owns drift detection.

### Reverse-proxy prefixes — ADR 0018 Phase 2.8 extension

`KodikUpstreamProxyFilter` will be generalised (or duplicated) into a `JutsuUpstreamProxyFilter` matching:

```
/api/v1/sources/jutsu/        (JutsuApiController — catalog, search, anime, episode, notice, drift)
/api/v1/sources/jutsu/stream  (JutsuStreamProxyController, canonical)
/api/v1/providers/jutsu/stream (JutsuStreamProxyController, legacy alias)
```

The `/api/v1/source-events/` prefix stays multi-tenant in the orinuno gateway: the filter routes by request path inspection to either source-kodik or source-jutsu when both standalone services are deployed. **Implementation note**: when the project lands its second per-source service, factor the proxy filter into a `SourceUpstreamProxyFilter` config-driven by a per-prefix backend map so further per-source services (Aniboom, Sibnet, …) plug in without code changes.

### Schema migration (jut.su tables)

Tables moving to `orinuno-source-jutsu`:

```
jutsu_title           — L1 row per anime entry (catalog discovery state)
jutsu_episode         — per-episode metadata
jutsu_film            — film-shaped jut.su rows
jutsu_sync_state      — singleton row, full-crawl resume / notice-walk cursor
```

Like Kodik, fresh `orinuno-source-jutsu` deploys bootstrap the schema in their own MySQL DB / schema (`orinuno_source_jutsu` per the convention from `scripts/db-init/01-create-source-schemas.sql`). Existing prod data is migrated by exporting from `orinuno`'s schema into the new one; details are downstream of Phase 3 prod traffic timing.

### Playwright live-fallback — STAYS IN `orinuno-source-jutsu`

`JutsuLiveFallbackService` + `JutsuFallbackCircuitBreaker` + `JutsuFallbackNegativeCache` move with the rest of the jut.su code into the new service. Consequences:

- `orinuno-source-jutsu` Dockerfile runtime needs Playwright + Chromium (currently the only consumer; orinuno-app's Phase 2.9 `INSTALL_PLAYWRIGHT` arg defaulted to `false` after the Kodik smoke fix — set it `true` for the jut.su runtime image, or split the runtime into a dedicated `source-jutsu-runtime` stage in `Dockerfile`).
- The Playwright-driven fallback path is the single largest source of OOM risk in jut.su today; isolating it in a separate JVM means a Chromium leak can crash the source service without blast on the gateway. Failure isolation per ADR 0018 trigger #3.
- Maintenance metrics: `jutsu_live_fallback_total{outcome=*}`, circuit-breaker state gauge — must publish on the standalone service's Prometheus surface so the dashboard / alert rules in `observability/grafana/` keep working with the same series labels.

### Event contract — identical to Kodik (ADR 0017)

jut.su already emits `SourceCatalogEvent` via the same in-process emitter today (`JutsuCatalogIngestion` deletion in Phase 5.6 cut over to source-event polling). The standalone service inherits the same `SourceEventController` projection logic as `orinuno-source-kodik` (Phase 2.6), parameterised on the jut.su `JutsuTitle` + `JutsuEpisode` repositories. Wire-format is the existing sealed `SourceCatalogEvent` family, no breaking changes.

### REST surface stability — identical promise

External callers — the external aggregator'shypothetical `downstream jut.su consumer` (none today), the demo UI's `/jutsu` route, future OSS consumers — hit the orinuno gateway. Direct calls to `orinuno-source-jutsu` are an optional perf/independence shortcut, not a correctness requirement.

### Reactor changes

- New top-level Maven module `orinuno-source-jutsu/` (sibling of `orinuno-source-kodik/`).
- Activate it under the existing `full-split` profile in the root `pom.xml`; the `monolith` profile excludes it (same pattern as kodik).
- `jutsu-sdk` (already extracted via ADR 0012) is the upstream dep. **No `jutsu-sdk-spring-boot-starter` exists today** — decision: either build one symmetric to kodik (extra module), or keep the autowiring inline in `orinuno-source-jutsu` since jut.su has fewer beans. Recommendation: **skip the starter** until a second jut.su consumer materialises (YAGNI per ADR 0016 §"Boundary discipline" — starters only when there's actually a second host).
- Docker target `source-jutsu-runtime` in the shared `Dockerfile` (mirrors `source-kodik-runtime` shape but installs Playwright/Chromium for the live fallback).
- `docker-compose.yml` adds `source-jutsu` service on host ports `8091`/`8092` (internal `8086`/`8087`, same template as source-kodik).
- `scripts/db-init/01-create-source-schemas.sql` un-comments the `orinuno_source_jutsu` block.

### Phase 4 roadmap (subset of ADR 0018 §"Roadmap")

| # | Item | Mirrors Kodik step | Notes |
|---|---|---|---|
| 4.1 | `orinuno-source-jutsu/` module skeleton | 2.1 | Spring Boot fat jar; depends on `jutsu-sdk` |
| 4.2 | Liquibase changelog migration | 2.2 | Copy `jutsu_*` SQL into `orinuno-source-jutsu/src/main/resources/db/changelog/jutsu/` |
| 4.3 | MyBatis repos + mappers | 2.3 | `JutsuTitleRepository`, `JutsuEpisodeRepository`, `JutsuFilmRepository`, `JutsuSyncStateRepository` |
| 4.4 | Controllers slice (`JutsuApiController`, `JutsuStreamProxyController`) | 2.4 | URLs preserved (canonical + legacy) |
| 4.4d | Slim `HealthController` | 2.4d | Mirror source-kodik's: jut.su SDK drift counters + token state (jut.su has no token registry but has sync-state) |
| 4.5 | Notice-walk + full-crawl schedulers | 2.5 equivalent | `JutsuCatalogSyncService` + `JutsuNoticeWalkScheduler` + `JutsuCatalogSyncScheduler` |
| 4.6 | `SourceEventController` jut.su projection | 2.6 | `JutsuTitle` → `SourceCatalogEvent.{TitleObserved,SeriesDiscovered}` |
| 4.7 | Live-fallback service + circuit breaker + negative cache | new for jut.su | Brings Playwright into source-jutsu runtime |
| 4.8 | Reverse-proxy in `orinuno` | 2.8 | Extend filter or factor into `SourceUpstreamProxyFilter` (see §"Reverse-proxy prefixes") |
| 4.9 | `docker-compose.yml` + Dockerfile target | 2.9 | host ports 8091/8092; `INSTALL_PLAYWRIGHT=true` on source-jutsu-runtime |
| 4.10 | `-P monolith` profile keeps skipping it | 2.10 | `docker-compose.monolith.yml` overlay marks `source-jutsu` `profiles: ["never"]` |
| 4.11 | `meter`'s `JutsuRemoteEventPoller` | 5.5 mirror | Add second poller in `meter/` symmetric to `KodikRemoteEventPoller`; reuse `RemoteSourceWatermarkRepository` with `sourceType="jutsu"` |
| 4.12 | Hybrid-fallback guards | new | Preserve the existing `jutsu_live_fallback_total{outcome=*}` metric + alert rules |

### REST cutover recipe

External consumers reach jut.su via the orinuno gateway by default — no code change required when Phase 4.8's filter is enabled. To bypass:

- demo UI — `VITE_API_URL` already empty (relative paths through orinuno's reverse-proxy); no change.
- Any future Kin `downstream jut.su consumer` — set `SOURCEJUTSU_BASE_URL=http://orinuno-source-jutsu:8086` to bypass orinuno hop. Endpoint paths and shapes are identical.

## Considered alternatives

### Skip jut.su standalone, keep it in `orinuno-app` indefinitely
- Pros: zero migration cost, no new service to operate.
- Cons: HTML drift continues blocking other releases on a single deployment unit; OSS contributors can't ship jut.su-only deploys; Playwright OOM risk continues to cross-contaminate the gateway.
- Verdict: **rejected** — the ADR 0018 triggers all apply to jut.su as much as Kodik. Delaying is the only argument, not skipping.

### Wait until Phase 5 catalog is fully battle-tested
- Pros: lowers concurrent change surface; one large refactor at a time.
- Cons: Phase 5 catalog cutover already landed (ADR 0018 Phase 5.1–5.11 ✅). The two splits are decoupled — jut.su standalone doesn't touch catalog write-path. Phase 3 prod-stability gate of source-kodik is the real blocker, not catalog.
- Verdict: **rejected** — ADR 0018 Phase 3 is the gate, not Phase 5.

### Build `jutsu-sdk-spring-boot-starter` symmetric to Kodik
- Pros: structural symmetry across the reactor; future second host (CLI tool, indexer) gets free wiring.
- Cons: no second host today. YAGNI cost — extra Maven module, extra autoconfigure cycle to maintain, same self-referencing cycle trap that bit Phase 5.10 and Phase 1.6 starter.
- Verdict: **rejected** — inline `@Configuration` in `orinuno-source-jutsu` is enough until a second host materialises. Re-decide when it does.

### Defer Playwright to a sidecar / dedicated browser service
- Pros: cleaner JVM, Playwright OOM truly isolated, no Chromium in the main JRE image.
- Cons: extra network hop, extra deploy unit, complicates OSS docker-compose stack (browser-as-a-service is one container too many for "casual contributor" DX per ADR 0016).
- Verdict: **rejected for now** — keep Playwright in-process inside `orinuno-source-jutsu` (failure isolation from orinuno-app is enough); revisit if Chromium OOMs prove to be the dominant failure mode in source-jutsu prod metrics.

## Blocked on

ADR 0018 Phase 3 — 14 days of `orinuno-source-kodik` prod traffic with zero downtime + error rate at or below baseline. Until that gate clears, Phase 4 work is design-only. After it clears, execute the 4.* roadmap above.

Secondary: the reverse-proxy filter generalisation (§"Reverse-proxy prefixes" recommendation) — if a third source is on the near-term horizon, do the factoring during Phase 4. If not, keep the per-source filter duplication; refactor when Aniboom/Sibnet actually need it.

## Tracker

| Item | Status |
|------|--------|
| ADR 0019 + index update | ✅ this PR |
| Phase 3 prod gate cleared | 🗑️ dropped (pre-prod refactor mode) |
| Phase 4.1 module skeleton | ⏳ pending |
| Phase 4.2 schema migration | ⏳ pending |
| Phase 4.3 repos + mappers | ⏳ pending |
| Phase 4.4 controllers | ⏳ pending |
| Phase 4.4d HealthController slice | ⏳ pending |
| Phase 4.5 sync schedulers | ⏳ pending |
| Phase 4.6 SourceEventController projection | ⏳ pending |
| Phase 4.7 live-fallback migration | ⏳ pending |
| Phase 4.8 reverse-proxy filter | ⏳ pending |
| Phase 4.9 docker-compose + Dockerfile | ⏳ pending |
| Phase 4.10 monolith overlay update | ⏳ pending |
| Phase 4.11 meter `JutsuRemoteEventPoller` | ⏳ pending |
| Phase 4.12 fallback metrics regression test | ⏳ pending |
