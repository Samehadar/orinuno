# Orinuno

[![CI](https://github.com/Samehadar/orinuno/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/Samehadar/orinuno/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Samehadar/orinuno/actions/workflows/codeql.yml/badge.svg?branch=master)](https://github.com/Samehadar/orinuno/actions/workflows/codeql.yml)
[![Docs](https://github.com/Samehadar/orinuno/actions/workflows/docs-deploy.yml/badge.svg?branch=master)](https://samehadar.github.io/orinuno/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F.svg?logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)

Standalone open-source service for parsing video content from [Kodik](https://kodik.info). Provides a REST API for searching, decoding video links, exporting structured content, and streaming HLS manifests.

> **Status:** experimental · educational · not affiliated with Kodik.
> See [DISCLAIMER.md](./DISCLAIMER.md) before running it anywhere outside a sandbox.

## Quick Start

```sh
git clone https://github.com/Samehadar/orinuno.git
cd orinuno
cp .env.example .env            # set KODIK_TOKEN
docker compose up -d
```

Swagger UI: <http://localhost:8085/swagger-ui.html> · Demo UI: <http://localhost:3000>

## Repository layout

Orinuno is a multi-module Maven reactor. ADR 0018 split the Kodik path into
its own standalone deployable; ADR 0019/0020/0021 carried the same template
to jut.su and moved L2/L3 catalog ownership into `meter`. The reactor today
ships these modules:

| Module | Purpose | Spring? |
|--------|---------|---------|
| [`orinuno-app/`](./orinuno-app/) | Thin gateway + cross-source orchestrator. Reverse-proxies `/api/v1/parse/`, `/api/v1/stream/`, `/api/v1/hls/`, `/api/v1/download/`, `/api/v1/export/`, `/api/v1/calendar/` to per-source services; owns the cross-source `MultiSourceController`, `CatalogController` (against the meter-readonly DS), `SourcesController`, `ProvidersController`, demo UI surface, and JutSu fallback decoder. No L1 / L2 tables of its own. | ✅ Boot |
| [`orinuno-source-kodik/`](./orinuno-source-kodik/) | Standalone Kodik deployable (ADR 0018 Phase 2 + ADR 0021 Blocks C/D/E2). Owns the `orinuno_source_kodik` L1 schema (`kodik_*` tables, parse-request queue, decoder cache, calendar state/outbox) and serves `/api/v1/parse/*`, `/api/v1/decoder/*`, `/api/v1/stream/*`, `/api/v1/hls/*`, `/api/v1/download/*`, `/api/v1/export/*`, `/api/v1/embed/*`, `/api/v1/reference/*`, `/api/v1/calendar/*`, `/api/v1/source-events/*`. | ✅ Boot |
| [`orinuno-source-jutsu/`](./orinuno-source-jutsu/) | Standalone jut.su deployable (ADR 0019). Owns the `orinuno_source_jutsu` L1 schema (`jutsu_title`, `jutsu_episode`, `jutsu_film`, `jutsu_sync_state`) and serves `/api/v1/sources/jutsu/*` (catalog / search / anime info / episode meta / notice feed / drift). | ✅ Boot |
| [`meter/`](./meter/) | OSS catalog collector (ADR 0018 Phase 5 + ADR 0020). Subscribes to `/api/v1/source-events/ready` on every per-source service, single-writer of the shared `orinuno_catalog` schema (`catalog_content`, `catalog_content_external_id`, `catalog_episode`, `episode_source`, `episode_video`). orinuno-app reads L2/L3 over the read-only `catalogReadJdbcTemplate` DS. | ✅ Boot |
| [`orinuno-source-contract/`](./orinuno-source-contract/) | Sealed `SourceCatalogEvent` contract shared with meter consumers (ADR 0017). | ❌ pure Java |
| [`kodik-sdk/`](./kodik-sdk/) | Spring-free Kodik HTTP/decoder/token SDK + drift detector. | ❌ Reactor + WebFlux only |
| [`kodik-sdk-spring-boot-starter/`](./kodik-sdk-spring-boot-starter/) | Auto-config glue: wires kodik-sdk beans into any Spring Boot host. | ✅ auto-config |
| [`jutsu-sdk/`](./jutsu-sdk/) | Standalone JutSu client: DLE auth, sticky cookies, 1 RPS rate-limit, premium decode. | ❌ Reactor + WebFlux only |
| [`sibnet-sdk/`](./sibnet-sdk/) | Standalone Sibnet decoder (`shell.php` + `player.src(...)` regex). Stateless. | ❌ Reactor + WebFlux only |
| [`aniboom-sdk/`](./aniboom-sdk/) | Standalone Aniboom decoder (`<input id="video-data">` + Jackson). Stateless. | ❌ Reactor + WebFlux only |

### Build profiles

The reactor ships two Maven profiles (root `pom.xml`):

- **`full-split`** *(default, no flag needed)* — builds every module
  including `orinuno-source-kodik`, `orinuno-source-jutsu`, and `meter`.
  Matches the `docker compose up` production shape: orinuno-app
  reverse-proxies per-source routes to the standalone services and reads
  L2/L3 catalog data over the meter-readonly DS.
- **`-P monolith`** — builds the libraries + `orinuno-app` only, skipping
  the per-source deployables and `meter`. After ADR 0021 closed the
  write-path move, monolith mode is a thin-gateway-only fallback:
  the per-source routes (`/api/v1/parse/`, `/api/v1/decoder/`,
  `/api/v1/stream/`, `/api/v1/hls/`, `/api/v1/download/`, `/api/v1/export/`,
  `/api/v1/embed/`, `/api/v1/reference/`, `/api/v1/calendar/`,
  `/api/v1/sources/jutsu/*`) need a remote target — set
  `ORINUNO_SOURCE_KODIK_BASE_URL` / `ORINUNO_SOURCE_JUTSU_BASE_URL` to a
  running per-source service and the `*UpstreamProxyFilter`
  (`@ConditionalOnProperty`) wires up. With the env unset the filters do
  not register and Spring returns 404. The cross-source gateway endpoints
  (`MultiSourceController`, `CatalogController`, `SourcesController`,
  `ProvidersController`) stay operational; L2/L3 reads require
  `orinuno.catalog-read.url` pointing at a populated catalog DB
  (otherwise the `@ConditionalOnBean(name = "catalogReadJdbcTemplate")`
  controllers also stay unwired and return 404).
  Compose overlay for single-container dev:
  ```sh
  mvn -P monolith clean package
  docker compose -f docker-compose.yml -f docker-compose.monolith.yml up
  ```

### Multi-instance orinuno (Phase 5.8)

orinuno-app is stateless w.r.t. catalog (the shared catalog DB is the
source of truth, per-instance Caffeine caches are read-side). Scale
horizontally behind nginx with the `scale` overlay:

```sh
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --scale app=3
curl http://localhost:8084/api/v1/health        # nginx round-robins across replicas
```

Per-instance cache lag is bounded by `orinuno.catalog.cache.expire-after-write-seconds`
(default 300s) — two replicas may serve different versions of a catalog row
during that window. Acceptable for catalog reads; documented in ADR 0018
Phase 5.8.

Provider SDKs are designed for direct consumption — depend on the SDK
artefact you need without pulling in MySQL, Liquibase, MyBatis, or any
orinuno-app type. See the per-SDK READMEs for the public API and config
contracts. Migration history and contract details live in
[`CHANGELOG.md`](./CHANGELOG.md) and [`docs/adr/`](./docs/adr/).

## 📚 Documentation

Full documentation is published at **<https://samehadar.github.io/orinuno/>**.

- [Getting Started](https://samehadar.github.io/orinuno/getting-started/quick-start/) — install, configuration, prerequisites.
- [Architecture](https://samehadar.github.io/orinuno/architecture/overview/) — C4 context, component view, the eight-step video decoder, HLS manifest handling, and the Playwright-based download pipeline.
- [API Reference](https://samehadar.github.io/orinuno/api/overview/) — endpoints, auth, pagination, and a generated reference from the OpenAPI snapshot.
- [Operations](https://samehadar.github.io/orinuno/operations/proxy-pool/) — proxy pool, TTL refresh, background tasks, monitoring.
- [Development](https://samehadar.github.io/orinuno/development/contributing/) — contributing, project structure, testing, code style.
- [Legal](https://samehadar.github.io/orinuno/legal/disclaimer/) — disclaimer, responsible use, takedown requests, license.

## Responsible use

Orinuno ships with conservative rate-limit defaults and is **not** intended for mass scraping, public mirrors, or commercial re-distribution of third-party video content. Please read [Responsible Use](https://samehadar.github.io/orinuno/legal/responsible-use/) before tuning the knobs upward.

## Links

- [DISCLAIMER](./DISCLAIMER.md) · [LICENSE](./LICENSE) · [CONTRIBUTING](./CONTRIBUTING.md) · [SECURITY](./SECURITY.md) · [CODE_OF_CONDUCT](./CODE_OF_CONDUCT.md) · [CHANGELOG](./CHANGELOG.md)
- Architecture diagrams (Mermaid + PlantUML source): [ARCHITECTURE.md](./ARCHITECTURE.md), [docs/](./docs/)
- ADR index: [docs/adr/index.md](./docs/adr/index.md)

## Takedowns

If you are a rights holder or platform representative and believe content in this repository should be adjusted or removed, open a `[takedown]`-prefixed issue at <https://github.com/Samehadar/orinuno/issues> or contact the maintainer via <https://lyutarevich.com/>. Reasonable requests will be handled in good faith.
