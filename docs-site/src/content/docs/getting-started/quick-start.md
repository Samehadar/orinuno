---
title: Quick Start
description: Run Orinuno locally in under five minutes with Docker Compose, or build from source with Maven.
---

The fastest way to run Orinuno is the bundled Docker Compose stack. A manual
setup is documented below for contributors who need to rebuild on every change.

## Docker Compose (recommended)

Requires Docker with Compose v2 and a Kodik API token.

```sh
git clone https://github.com/Samehadar/orinuno.git
cd orinuno
cp .env.example .env
# edit .env and set KODIK_TOKEN
docker compose up -d
```

Once the stack is up:

- REST API: `http://localhost:8085/api/v1/health`
- Swagger UI: `http://localhost:8085/swagger-ui.html`
- Demo UI: `http://localhost:3000`

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

mvn spring-boot:run
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
