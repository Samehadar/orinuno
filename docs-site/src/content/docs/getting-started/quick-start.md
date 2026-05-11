---
title: Quick Start
description: Run Orinuno locally in under five minutes with Docker Compose, or build from source with Maven.
---

The fastest way to run Orinuno is the bundled Docker Compose stack. A manual
setup is documented below for contributors who need to rebuild on every change.

## Step 0 — Kodik token bootstrap (mandatory)

Orinuno cannot make a single Kodik API call without a token. Both
docker-compose and manual runs need this seeded **before** start; otherwise
every parse / embed / decode endpoint returns `503` with
`error: registry empty`.

Two ways to seed:

1. **Environment variable (recommended for first run)** — set `KODIK_TOKEN`
   to a known-good value. The first boot copies it into the registry under
   `data/kodik_tokens.json` (tier `stable`) and probes its capabilities
   against `/list`, `/search`, `/get-player`.

   ```sh
   export KODIK_TOKEN=your_kodik_api_token
   ```

2. **Direct edit of `data/kodik_tokens.json`** — for ops / token rotation.
   The file is gitignored. Minimal shape (mirrors AnimeParsers' format —
   see [`data/TOKENS.md`](https://github.com/Samehadar/orinuno/blob/master/data/TOKENS.md)):

   ```json
   {
     "stable": [
       {
         "value": "<token>",
         "lastChecked": null,
         "note": "manually seeded",
         "functionsAvailability": {}
       }
     ],
     "unstable": [],
     "legacy": [],
     "dead": []
   }
   ```

After boot, verify with:

```sh
curl -sS http://localhost:8085/api/v1/health/tokens | jq '.liveCount'
# expect: > 0
```

If you want one call that also covers schema-drift and queue-depth, use the
[integration health endpoint](/orinuno/operations/kodik-parser-integration/#1-pre-flight-checklist):

```sh
curl -sS http://localhost:8085/api/v1/health/integration | jq
# expect: "status": "READY"
```

## Docker Compose (recommended)

Requires Docker with Compose v2 and a Kodik API token (see Step 0).

```sh
git clone https://github.com/Samehadar/orinuno.git
cd orinuno
cp .env.example .env
# edit .env and set KODIK_TOKEN
docker compose up -d
```

The default stack is the **full-split topology** (ADR 0018):

| Container | Host port | Role |
| --- | --- | --- |
| `orinuno-app` | 8085 (API), 8086 (actuator) | Public API gateway, multi-instance capable |
| `orinuno-source-kodik` | 8087 (API), 8088 (actuator) | Standalone Kodik service |
| `meter` | 8089 (API), 8090 (actuator) | OSS catalog collector (single DB writer) |
| `db` (MySQL 8.0) | 3316 | Shared MySQL — separate schemas per role |
| `demo` (nginx + Vue) | 3000 | Demo UI |

Once up:

- REST API: `http://localhost:8085/api/v1/health`
- Swagger UI: `http://localhost:8085/swagger-ui.html`
- Demo UI: `http://localhost:3000`
- Topology background: [Per-source service split](/orinuno/architecture/per-source-split/).

### Monolith mode (single-container dev)

For casual contributors who don't want the 5-container stack on their laptop:

```sh
mvn -P monolith clean package
docker compose -f docker-compose.yml -f docker-compose.monolith.yml up -d
```

The overlay drops `orinuno-source-kodik` and `meter` from the stack and
clears `ORINUNO_SOURCE_KODIK_BASE_URL` so `orinuno-app`'s own controllers
serve the Kodik routes locally. Trade-off: monolith mode loses
`/api/v1/catalog/*` (the canonical L3 surface lives only in `meter`).
Per-source endpoints (`/api/v1/kodik/*`, `/api/v1/embed/*`, etc.) keep
working.

### Multi-instance gateway (horizontal scale-out)

`orinuno-app` is stateless w.r.t. the canonical catalog and trivially
horizontally scalable. The `scale` overlay adds an nginx load balancer
on host port 8084:

```sh
docker compose -f docker-compose.yml -f docker-compose.scale.yml up -d --scale app=3
curl http://localhost:8084/api/v1/health   # nginx round-robins across replicas
```

Per-instance Caffeine cache TTL bounds the eventual-consistency window
between replicas (default 300 s, override
`orinuno.catalog.cache.expire-after-write-seconds`). See
[Per-source service split](/orinuno/architecture/per-source-split/#multi-instance-orinuno-app)
for the failure-isolation model.

## Manual run

Requires Java 21+, Maven 3.9+, and a local MySQL 8.0+ instance. See
[Prerequisites](/orinuno/getting-started/prerequisites/) for the full list.

```sh
export KODIK_TOKEN=your_kodik_api_token
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=orinuno
export DB_USERNAME=root
export DB_PASSWORD=root

# Multi-module reactor (PR3): the Spring Boot app lives in the orinuno-app submodule.
mvn -pl orinuno-app -am spring-boot:run
```

The service starts on port `8080`. Swagger UI is at
`http://localhost:8080/swagger-ui.html`.

## Smoke test

```sh
curl -sS http://localhost:8085/api/v1/health | jq
curl -sS -X POST http://localhost:8085/api/v1/parse/search \
  -H 'Content-Type: application/json' \
  -d '{"title":"Chainsaw Man","decodeLinks":true}'
curl -sS "http://localhost:8085/api/v1/export/ready?page=0&size=5"
```

If every call returns a 200 with a JSON body, the install is good.

## Next steps

- [Configuration](/orinuno/getting-started/configuration/) — the full `application.yml` reference.
- [API overview](/orinuno/api/overview/) — authentication, versioning, and base URLs.
- [Architecture overview](/orinuno/architecture/overview/) — how the pieces fit together.
