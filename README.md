# Orinuno

[![CI](https://github.com/Samehadar/orinuno/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/Samehadar/orinuno/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Samehadar/orinuno/actions/workflows/codeql.yml/badge.svg?branch=master)](https://github.com/Samehadar/orinuno/actions/workflows/codeql.yml)
[![Docs](https://github.com/Samehadar/orinuno/actions/workflows/docs-deploy.yml/badge.svg?branch=master)](https://samehadar.github.io/orinuno/)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F.svg?logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)

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

- [DISCLAIMER](./DISCLAIMER.md) · [LICENSE](./LICENSE) · [CONTRIBUTING](./CONTRIBUTING.md) · [SECURITY](./SECURITY.md) · [CODE_OF_CONDUCT](./CODE_OF_CONDUCT.md)
- Architecture diagrams (Mermaid + PlantUML source): [ARCHITECTURE.md](./ARCHITECTURE.md), [docs/](./docs/)

## Takedowns

If you are a rights holder or platform representative and believe content in this repository should be adjusted or removed, open a `[takedown]`-prefixed issue at <https://github.com/Samehadar/orinuno/issues> or contact the maintainer via <https://lyutarevich.com/>. Reasonable requests will be handled in good faith.
