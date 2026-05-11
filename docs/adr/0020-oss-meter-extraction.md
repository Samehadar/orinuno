# ADR 0020 — OSS `meter` extraction; shared catalog DB; `orinuno` multi-instance read-path

Status: **Accepted** — landed across ADR 0018 Phase 5.1–5.11 (commits `db7fd05` through `092cb7f` on `feat/per-source-services-split`).
Date: 2026-05-12
Refines: ADR 0018 (this ADR formalises the Phase 5 architectural choices that ADR 0018 sketched).
Companion of: ADR 0017 (event contract — unchanged), ADR 0019 (jut.su split — symmetric meter wiring).

## Context

ADR 0018 introduced "OSS `meter` as a separate service" and "`orinuno` as multi-instance API gateway over shared catalog DB" in §"Target topology" but deferred the *how* to Phase 5. Phase 5 then landed in 12 commits (Phase 5.1–5.11) without a dedicated ADR because each step was small. This ADR records the resulting design as a single referable decision so future readers and contributors have one document to point at.

**Why a separate ADR (not just notes in 0018):**

- Phase 5 is the *non-trivial* part of the per-source split — the meter / orinuno boundary, the shared-DB pattern, the read-only datasource + Caffeine cache, and the Kafka deferral are durable decisions that other features will keep bumping into.
- ADR 0019 (jut.su split) and any future per-source service ADR will refer to "meter's contract" as if it were a stable surface; that surface needs a primary citation.
- Future Phase 6 (Kafka event sourcing) lives or dies on the assumptions encoded here; a future ADR 0021 will supersede *this* ADR's "no HTTP between orinuno and meter" clause, not 0018's high-level topology.

## Decision

### 1. `meter` is a separate Spring Boot deployable, symmetric to the external aggregator'sproprietary `external meter`

The OSS `meter/` module is the *single writer* of the canonical catalog (`catalog_content`, `catalog_content_external_id`, `catalog_episode`, `catalog_episode_source_link`). It owns:

- the catalog Liquibase changelog (`meter/src/main/resources/db/catalog-changelog/`) — only meter applies migrations on the shared schema;
- the `CatalogPublicApi` + `CatalogIdentityResolver` + `CatalogSinkEventEmitter` write-path (moved from `orinuno-app` in Phase 5.3);
- the `*RemoteEventPoller` workers that poll each per-source service's `/api/v1/source-events/ready` and reconcile into the canonical store (Phase 5.5 for Kodik; ADR 0019 Phase 4.11 for jut.su).

Symmetry with the external aggregator'sproprietary meter is the contract that ADR 0017 promised: both meters (Kin + OSS) consume the same `SourceCatalogEvent` wire shape. They differ only in deployment ownership (proprietary out-of-repo in `external aggregator/`, OSS in this repo) and in their consumers (external meter feeds consumer-internal catalog APIs; OSS meter feeds `orinuno`'s read-path).

### 2. Catalog read-path = shared MySQL DB, NOT an HTTP API

`orinuno` reads `catalog_*` directly from the same MySQL the meter writes to, via a second `JdbcTemplate` + read-only MyBatis layer (`orinuno-app/.../catalog/readonly/`, Phase 5.4). Wrapped in a Caffeine cache (Phase 5.7a, 5 min TTL + 1 min stale-while-revalidate by default). `CatalogController` (Phase 5.7) serves `/api/v1/catalog/*` end-to-end without an HTTP hop to meter.

**Why no HTTP between them:**

- Removes the inter-service round-trip from the hot read path entirely — sub-millisecond Caffeine hits, p99 bounded by one MySQL `SELECT` instead of `meter HTTP → meter MySQL`.
- meter availability no longer gates `orinuno` read-path. meter outages freeze *updates* (catalog stops getting new rows), but `orinuno` keeps serving everything already in the DB + cache. This matches the failure-isolation promise of ADR 0018 trigger #3 at the catalog layer.
- `orinuno` becomes truly stateless w.r.t. catalog and trivially horizontally scalable (Phase 5.8 acceptance test).

**Cost the design accepts:**

- meter's `catalog_*` schema is a shared contract with `orinuno`'s SQL queries — schema migrations require coordination. The current cost is low because both modules live in the same monorepo; if meter spins out of this repo later, the schema contract gets pinned via a generated DDL artefact or migrates to Phase 6's Kafka topic shape.
- multi-region / multi-DC deploys eventually want read-locality. The shared-DB pattern doesn't preclude this — MySQL group replication / replica reads cover it — but the simple OSS docker-compose stays single-DB until a real deployer needs more.

### 3. DB user separation enforces the boundary at the storage layer

Phase 5.9 added MySQL grants:

```
orinuno_meter_writer    CRUD on orinuno_catalog.*
orinuno_catalog_reader  SELECT-only on orinuno_catalog.*
```

`orinuno-app`'s read-only datasource connects as `orinuno_catalog_reader` so any future code change that accidentally injects a write through the wrong bean fails at the DB layer with a permission error, not silently corrupts catalog state. Defense-in-depth on top of the application-layer `@Repository` discipline.

### 4. Per-instance Caffeine cache is the eventual-consistency window

Cache config (Phase 5.7a, property-driven):

```
orinuno.catalog.cache.expire-after-write-seconds  = 300  (5 min)
orinuno.catalog.cache.refresh-after-write-seconds = 60   (SWR — async refresh)
orinuno.catalog.cache.max-size                    = 50000
```

Acceptable for catalog reads: Kodik refreshes ~once per dump cycle (~24h), jut.su updates incrementally over hours. 5 min lag between orinuno replicas is below the change rate of the underlying data. Negative results (`Optional.empty`) are cached too — stampede guard against repeated lookups of non-existent ids.

When multi-instance lag *does* matter (e.g. someone just searched, immediately reloads page, gets stale row from a different replica), the trade-off is documented in README §"Multi-instance orinuno" and the OSS deploy is expected to either accept it or tune `expire-after-write-seconds` down.

### 5. `monolith` profile keeps a path back

`mvn -P monolith` builds only the libraries + `orinuno-app`, skipping `meter` and the per-source services. Default profile (`full-split`) builds the full stack.

Monolith mode loses `/api/v1/catalog/*` (the canonical L3 surface lives only in meter post-Phase 5.6) but retains every per-source endpoint via `orinuno-app`'s in-process controllers. Documented in README §"Build profiles". Trade-off accepted because:

- contributor DX (single-container dev) trumps full feature parity for the OSS happy path;
- production deploys always use full-split; monolith is for kicking the tyres.

### 6. Kafka event sourcing deferred (future ADR 0021)

Phase 6 of ADR 0018 enumerated four triggers for migrating from shared-DB to Kafka-based event sourcing:

1. schema coupling pain (≥3 mapper breakage incidents/quarter);
2. multi-DC / read-locality latency;
3. read-load >80% Caffeine miss-rate or DB connection pool starvation;
4. second non-orinuno OSS consumer (Telegram bot, search indexer) wanting real-time catalog updates.

None have fired. The path forward is recorded so future-us doesn't re-litigate it under pressure:

- meter gains a transactional outbox (`catalog_event_outbox`) writing `CatalogChangeEvent` records in the same transaction as `catalog_*` mutations;
- a `MeterOutboxPublisher` drains the outbox into Kafka topic `orinuno.catalog.events`;
- `orinuno` consumes the topic into a local read store (per-replica embedded DB or in-memory + Kafka replay-from-earliest at startup);
- the read-only repository contract from Phase 5.4 keeps its current shape — only the underlying source swaps.

ADR 0021 (not yet written) will codify Phase 6 when a trigger fires.

## Considered alternatives

### Single OSS process: drop `orinuno`, rename to `meter`
- Pros: one deployable, no inter-service contract.
- Cons: loses multi-instance orinuno scaling (catalog reads tied to the meter ingestion JVM); blast-radius of a meter outage extends to the public API surface; OSS contributors who only want the catalog collector get the full HTTP gateway too.
- Verdict: **rejected** — separate concerns, separate deploys.

### HTTP API between `orinuno` and `meter` (no shared DB)
- Pros: cleaner contract — meter exposes `/api/v1/catalog/*` and orinuno is a pure HTTP gateway.
- Cons: extra hop in hot read path; meter availability blocks orinuno reads; multi-instance orinuno scaling forced into meter's JVM; doesn't actually clean up schema coupling (gRPC / REST contract is just as coupled as the SQL schema, often more brittle on JSON field renames).
- Verdict: **rejected** — see Decision §2 cost analysis.

### Kafka outbox from day 1 (Phase 6 pulled forward)
- Pros: cleaner separation, future-proof.
- Cons: Kafka broker in the OSS docker-compose stack scares casual contributors (ADR 0016 §"Reference projects"); catalog churn is too low today to justify the operational complexity; can be migrated *without data loss* later (outbox is additive).
- Verdict: **rejected for now**, see Phase 6 deferral.

### Per-instance write-back cache (instead of read-only cache)
- Pros: faster writes for orinuno-side updates.
- Cons: orinuno doesn't write catalog — the entire premise of "meter is the single writer" disappears. Also a footgun: two writers (meter + orinuno write-back) racing on `catalog_content`.
- Verdict: **rejected** — orinuno is read-only by design, Phase 5.9 grants enforce it.

### Liquibase-managed schema duplicated in both modules
- Pros: each module is self-contained.
- Cons: Liquibase tracks changesets by file + author + id; duplicate apply against the same DB re-runs everything. Phase 5.2a kept the modules in *separate schemas* (`orinuno` vs `orinuno_catalog`) for exactly this reason; consolidating ownership into `meter` later was straightforward because the schemas never overlapped.
- Verdict: **rejected** — meter owns the changelog, period.

## Consequences

- **Shipped (Phase 5.1–5.11):** meter module, catalog DDL in shared schema, read-only datasource + cache + controller in orinuno, DB user separation, multi-instance acceptance test, nginx LB overlay, monolith profile compatibility.
- **Pending:** Phase 5.12 (publish SDK + contract artefacts — deferred per ADR 0018 §"Roadmap"); Phase 5.13 (OSS docs-site rewrite — gated on user ask).
- **New invariants enforced in tests:**
  - `CatalogReaderGrantIT` — orinuno_catalog_reader is SELECT-only (regression guard against accidental GRANT-creep).
  - `OrinunoMultiInstanceCatalogIT` — two CatalogReadCache instances over the same DB stay independent under eviction.
  - `CatalogContentReadRepositoryIT` — SELECT-only access through the dedicated `catalogReadJdbcTemplate` bean.
  - `CatalogChangelogApplyIT` — meter's changelog applies cleanly on a fresh MySQL container.
- **Operational changes:**
  - deploy order requires `meter` up first (Liquibase migrations) before `orinuno-app` (read-only reader); enforced via docker-compose `depends_on: db (condition: service_healthy)` for both.
  - `orinuno` is horizontally scalable behind nginx (`docker compose -f docker-compose.yml -f docker-compose.scale.yml up --scale app=N`).

## Blocked on

Nothing. Phase 5.12 (publish artefacts) and Phase 5.13 (docs-site) are post-decision polish, not architectural gates.

## Tracker

| Item | Status |
|------|--------|
| ADR 0020 + index update | ✅ this PR |
| Phase 5.1 meter skeleton (`db7fd05`) | ✅ done |
| Phase 5.2a catalog Liquibase in shared schema (`977a451`) | ✅ done |
| Phase 5.3 catalog packages → meter (`63ce1f3`) | ✅ done |
| Phase 5.4 read-only datasource + repo (`d31a4c8`) | ✅ done |
| Phase 5.5 KodikRemoteEventPoller → meter (`63ce1f3`) | ✅ done |
| Phase 5.6 catalog write-path deleted from orinuno-app (`63ce1f3`) | ✅ done |
| Phase 5.7 CatalogController over cache (`c2b19fe`) | ✅ done |
| Phase 5.7a Caffeine read-cache (`f29f8a3`) | ✅ done |
| Phase 5.8 multi-instance acceptance + nginx LB (`092cb7f`) | ✅ done |
| Phase 5.9 DB user separation (`4429a9d`) | ✅ done |
| Phase 5.10 monolith profile fix (`81f3278`) | ✅ done |
| Phase 5.11 meter in docker-compose (`9bc52d1`) | ✅ done |
| Phase 5.12 publish SDK + contract artefacts | ⏳ deferred |
| Phase 5.13 OSS docs-site rewrite | ⏳ deferred (explicit ask required) |
| Future ADR 0021 (Kafka event sourcing) | ⏳ deferred (no trigger fired) |
