# Orinuno

[![CI](https://github.com/Samehadar/orinuno/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/Samehadar/orinuno/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Samehadar/orinuno/actions/workflows/codeql.yml/badge.svg?branch=master)](https://github.com/Samehadar/orinuno/actions/workflows/codeql.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](./LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.4](https://img.shields.io/badge/Spring%20Boot-3.4-6DB33F.svg?logo=spring&logoColor=white)](https://spring.io/projects/spring-boot)

Standalone open-source service for parsing video content from [Kodik](https://kodik.info). Provides REST API for searching, decoding video links, exporting structured content data, and streaming HLS manifests.

> **Status:** experimental · educational purpose · not affiliated with Kodik.
> See [DISCLAIMER.md](./DISCLAIMER.md) before you run this in production.

## Features

### Kodik API Client
- **Full Kodik API coverage** -- 7 endpoints: `/search`, `/list`, `/translations/v2`, `/genres`, `/countries`, `/years`, `/qualities/v2`
- **70+ search filters** -- title, IDs (Kinopoisk, IMDb, Shikimori, MDL, WorldArt), types, year, genres, ratings, actors, studios, and more
- **Raw Response First architecture** -- all API responses received as raw `Map<String, Object>` before DTO conversion; unknown fields are detected and logged (`KodikResponseMapper`)
- **Schema drift detection** -- automatic WARN logging when Kodik adds new fields or changes response structure
- **Rate limiting** -- token-bucket rate limiter (`KodikApiRateLimiter`) prevents exceeding Kodik API quotas
- **Paginated list** -- `/list` endpoint with `nextPageUrl` cursor-based pagination

### Video Decoding
- **Brute-force ROT cipher** -- tries all 26 shifts (0-25) with cached last working shift; auto-detects when Kodik changes the cipher
- **Dynamic endpoint discovery** -- resolves video-info POST path from player JS with fallback chain (`/ftor`, `/kor`, `/gvi`, `/seria`) and caching
- **Proxy-aware decoding** -- all decoder HTTP calls (iframe, player JS, video-info POST) route through proxy pool with automatic direct fallback
- **Retry with backoff** -- failed decodes are retried automatically with exponential backoff

### Content & Metadata
- **MaterialData storage** -- Kodik's `material_data` saved as JSON column + indexed fields (kinopoisk_rating, imdb_rating, shikimori_rating, genres, blocked_countries)
- **Geo-block detection** -- dual-layer: CDN URL pattern (`/s/m/`) detection + `blocked_countries` from API response
- **Structured export** -- content grouped by seasons/episodes/translations, ready for external consumers

### Video Download & Streaming
- **Video download via Playwright** -- headless Chromium intercepts HLS manifests from CDN, bypassing anti-bot protections
- **Parallel HLS download** -- `.ts` segments downloaded concurrently via `java.net.http.HttpClient` (configurable concurrency)
- **Automatic remux** -- downloaded `.ts` segments are remuxed to `.mp4` via ffmpeg (stream copy, no re-encoding)
- **Video streaming proxy** -- serves local files or downloads on-the-fly, with Range request support
- **HLS manifest API** -- REST endpoints for getting fresh m3u8 URLs and absolutized manifests (for integration with external downloaders)
- **Download progress tracking** -- real-time segment/byte progress via polling API

### Infrastructure
- **TTL link management** -- automatic refresh of expired CDN links (configurable TTL)
- **API key auth** -- optional `X-API-KEY` header-based authentication
- **Proxy rotation** -- configurable proxy pool with round-robin rotation and failure tracking
- **Swagger UI** -- interactive API documentation at `/swagger-ui.html`
- **Health monitoring** -- decoder success rate, proxy pool status, Prometheus metrics
- **Demo UI** -- Vue.js SPA with search, download progress, and built-in video player

## Tech Stack

- Java 21, Spring Boot 3.4.6, WebFlux
- MyBatis + MySQL 8.0 + Liquibase
- Playwright for Java (headless Chromium for CDN bypass)
- ffmpeg (`.ts` → `.mp4` remux)
- SpringDoc OpenAPI (Swagger UI)
- Jsoup, Jackson, Lombok
- Micrometer + Prometheus
- Testcontainers + JUnit 5 + AssertJ
- Vue.js 3 + Vite + Tailwind CSS (demo UI)

## Kodik API References (external)

Неофициальные справки и ссылки на источники собраны в [docs/KODIK_API_SOURCES.md](docs/KODIK_API_SOURCES.md). Там же лежат локальные копии в [docs/vendor/](docs/vendor/) для офлайн-чтения.

## Quick Start

### With Docker Compose (recommended)

```bash
cp .env.example .env
# edit .env and set KODIK_TOKEN
docker compose up -d
```

The service starts on port `8085`. Swagger UI: http://localhost:8085/swagger-ui.html
Demo UI: http://localhost:3000

### Manual

Prerequisites: Java 21+, MySQL 8.0+, Maven 3.9+

```bash
export KODIK_TOKEN=your_kodik_api_token
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=orinuno
export DB_USERNAME=root
export DB_PASSWORD=root

mvn spring-boot:run
```

The service starts on port `8080`. Swagger UI: http://localhost:8080/swagger-ui.html

## Tests

### Unit Tests

Запускаются без внешних зависимостей (Kodik API не вызывается, БД не нужна):

```bash
mvn test
```

Включают:
- `KodikVideoDecoderServiceTest` -- ROT cipher с разными shift'ами, Base64 decode, full decode flow
- `ParserServiceTest` -- логика парсинга и сохранения
- `ContentControllerTest` -- REST-контроллер через `WebTestClient.bindToController`
- `ExportDataServiceTest` -- формирование экспортных DTO
- `ProxyProviderServiceTest` -- round-robin ротация прокси
- `OpenSourceGuardTest` -- проверка отсутствия зависимостей от приватных проектов

### Live Integration Tests (Testcontainers + реальный Kodik API)

Требуют `KODIK_TOKEN` и Docker (для MySQL через Testcontainers):

```bash
KODIK_TOKEN=your_token mvn test -Dtest=KodikLiveIntegrationTest
```

Полный E2E цикл: search → save → list → decode → export.

### Kodik API Stability Tests

**37 тестов** на реальный Kodik API. Проверяют структурную целостность ответов и обнаруживают schema drift. Не требуют БД -- только HTTP-клиент.

```bash
KODIK_TOKEN=your_token mvn test -Dtest=KodikApiStabilityTest
```

Покрытие по endpoint'ам:

| Endpoint | Тестов | Что проверяется |
|----------|--------|-----------------|
| `/search` | 18 | title (рус/англ/яп), shikimori_id (5 ID), kinopoisk_id (3 ID), imdb_id (3 ID), types (4 типа), with_seasons, translation_type, camrip filter, year filter, strict mode, not_blocked_in, genres, limit=1, nonexistent content, invalid token |
| `/search` + material_data | 3 | Рейтинги для 5 аниме, типы полей (numeric, list), фильм (Matrix) |
| `/list` | 8 | sort×order (6 комбинаций), types (5 типов), pagination (next_page), with_material_data, year filter, subtitles filter |
| `/translations/v2` | 3 | Структура, известные озвучки (AniLibria), type (voice/subtitles) |
| `/genres` | 3 | Структура, наличие жанров, genres_type filter |
| `/countries` | 2 | Структура, наличие стран |
| `/years` | 2 | Структура, наличие 2023/2024 |
| `/qualities/v2` | 2 | Структура, наличие качеств |

Каждый тест включает **schema drift detection**: ключи ответа сравниваются с известным набором полей. Новые неизвестные поля выводятся как `[SCHEMA DRIFT]` в stderr (тест не падает). Пропажа обязательного поля (`id`, `link`, `translation`) -- тест падает.

### Запуск всех тестов, требующих токен

```bash
KODIK_TOKEN=your_token mvn test -Dtest="KodikApiStabilityTest,KodikLiveIntegrationTest"
```

### Запуск только unit-тестов (без токена, без Docker)

```bash
mvn test -Dtest="!KodikApiStabilityTest,!KodikLiveIntegrationTest,!VideoDownloadLiveIntegrationTest"
```

## API Endpoints

All endpoints are under `/api/v1/`. If `ORINUNO_API_KEY` is configured, all `/api/v1/content`, `/api/v1/parse`, `/api/v1/export`, and `/api/v1/hls` endpoints require the `X-API-KEY` header.

### Content (`/api/v1/content`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/content` | List content with pagination (sortBy, order) |
| GET | `/api/v1/content/{id}` | Get content by ID |
| GET | `/api/v1/content/{id}/variants` | Get episode variants |
| GET | `/api/v1/content/by-kinopoisk/{id}` | Find by Kinopoisk ID |

### Parse (`/api/v1/parse`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/parse/search` | Search and parse from Kodik |
| POST | `/api/v1/parse/decode/{contentId}?force=false` | Decode mp4 links (force=true re-decodes all) |

### Export (`/api/v1/export`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/export/{contentId}` | Full export package for specific content |
| GET | `/api/v1/export/ready?updatedSince=` | Content ready for export (with optional timestamp filter) |

### HLS (`/api/v1/hls`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/hls/{variantId}/url` | Get fresh m3u8 URL (triggers decode) |
| GET | `/api/v1/hls/{variantId}/manifest` | Get absolutized m3u8 manifest (download + absolutize relative URLs) |

### Download (`/api/v1/download`)

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/download/{variantId}` | Start async download (returns immediately, poll status) |
| POST | `/api/v1/download/content/{contentId}` | Download all variants for content |
| GET | `/api/v1/download/{variantId}/status` | Poll download progress (segments, bytes) |

### Stream (`/api/v1/stream`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/stream/{variantId}` | Stream video (local file or download-on-demand), supports Range |

### Health (`/api/v1/health`)

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/health` | General health |
| GET | `/api/v1/health/decoder` | Decoder statistics |
| GET | `/api/v1/health/proxy` | Proxy pool status |

## Configuration Reference

```yaml
orinuno:
  kodik:
    api-url: https://kodik-api.com       # Kodik API base URL
    token: ""                             # Your Kodik API token (required)
    request-delay-ms: 500                 # Delay between API requests (ms). Conservative default; tune responsibly (see "Responsible use")
  parse:
    rate-limit-per-minute: 30             # Max Kodik API calls per minute (token-bucket). Lower it to be gentler; never exceed the quota attached to your token
  decoder:
    timeout-seconds: 30                   # Decoder timeout per link
    max-retries: 3                        # Max decode retry attempts
    link-ttl-hours: 20                    # CDN link TTL before refresh
    refresh-interval-ms: 3600000          # How often to check for expired links (ms)
    refresh-batch-size: 50                # Max links to refresh per cycle
  security:
    api-key: ""                           # API key for auth (empty = disabled)
  cors:
    allowed-origins: "*"                  # Comma-separated origins or "*"
  proxy:
    enabled: false                        # Enable proxy rotation for player/CDN requests
    rotation-strategy: round-robin        # Proxy selection strategy
  storage:
    base-path: ./data/videos              # Local storage for downloaded videos
    max-disk-usage-mb: 10240              # Max disk usage (MB)
  playwright:
    enabled: true                         # Enable Playwright video fetcher
    headless: true                        # Headless Chromium mode
    page-timeout-seconds: 30              # Page operation timeout
    navigation-timeout-ms: 15000          # Navigation timeout
    video-wait-ms: 30000                  # Max wait for video URL interception
    hls-concurrency: 16                   # Parallel threads for HLS segment download
```

## Database

MySQL with Liquibase migrations. Tables:

| Table | Purpose | Key fields |
|-------|---------|------------|
| `kodik_content` | Parsed content metadata (unique by `kinopoisk_id`) | `material_data` (JSON), `kinopoisk_rating`, `imdb_rating`, `shikimori_rating`, `genres`, `blocked_countries` |
| `kodik_episode_variant` | Per-episode translation/quality variants with decoded mp4 links, TTL tracking, and local file paths | `mp4_link`, `mp4_link_decoded_at`, `local_filepath` |
| `kodik_proxy` | Proxy pool for rotation | `host`, `port`, `status`, `fail_count` |

## Architecture

Диаграммы сгенерированы из PlantUML-исходников в [`docs/`](docs/) и автоматически рендерятся в `docs/images/` через GitHub Actions ([`.github/workflows/render-diagrams.yml`](.github/workflows/render-diagrams.yml)). Подробные Mermaid-схемы и ER — в [ARCHITECTURE.md](ARCHITECTURE.md).

### Overview (C4 Container)

Обзорная диаграмма: consumer, границы сервиса, внешние системы (Kodik API / Player / CDN), MySQL, локальное хранилище видео.

![Architecture Overview](docs/images/0_architecture_overview.svg)

> Source: [docs/0_architecture_overview.puml](docs/0_architecture_overview.puml)

### Kodik API Flow

Работа с Kodik API: rate-limiter (token-bucket), raw-response-first, schema drift detection, upsert в БД. Покрывает `/search`, `/list`, `/translations/v2`, `/genres`, `/countries`, `/years`, `/qualities/v2`.

![Kodik API Flow](docs/images/1_kodik_api_flow.svg)

> Source: [docs/1_kodik_api_flow.puml](docs/1_kodik_api_flow.puml)

### Video Link Decoding (ROT13 + Base64, 8 steps)

Расшифровка видео-ссылок: iframe → JS params → динамическое определение POST-endpoint → ROT-cipher (brute-force по всем 26 shift'ам) → URL-safe Base64.

![Video Decoding](docs/images/2_video_decoding.svg)

> Source: [docs/2_video_decoding.puml](docs/2_video_decoding.puml)

### HLS Manifest Retrieval

Получение расшифрованного `.m3u8` по `GET /api/v1/hls/{variantId}/manifest`: decode → fetch CDN manifest (через proxy с fallback) → абсолютизация относительных URL → возврат плейлиста с полными URL на сегменты.

![HLS Manifest](docs/images/7_hls_manifest.svg)

> Source: [docs/7_hls_manifest.puml](docs/7_hls_manifest.puml)

### Video Download (Playwright + HLS segments)

Скачивание видео: Playwright (headless Chromium) перехватывает URL манифеста, параллельная загрузка `.ts` сегментов через `HttpClient` с cookies браузера, ремукс в `.mp4` через ffmpeg.

![Video Download](docs/images/6_video_download_playwright.svg)

> Source: [docs/6_video_download_playwright.puml](docs/6_video_download_playwright.puml)

> **Note:** SVG/PNG файлы в `docs/images/` создаются автоматически при push'е, меняющем `docs/*.puml`. Чтобы отрендерить локально — см. [ARCHITECTURE.md → Local rendering](ARCHITECTURE.md#local-rendering).

### Key Flows

1. **Search & Parse** -- `POST /api/v1/parse/search` → Kodik API (`/search` with 70+ filters) → save content + variants + material_data to DB
2. **Decode** -- `POST /api/v1/parse/decode/{id}` → fetch player iframe (via proxy) → extract JS params → resolve video-info endpoint (with fallback chain) → brute-force ROT decode → save mp4 links
3. **HLS Manifest** -- `GET /api/v1/hls/{id}/manifest` → fresh decode → fetch m3u8 → absolutize URLs → return playlist
4. **Export** -- `GET /api/v1/export/{id}` → structured JSON (seasons → episodes → variants)
5. **TTL Refresh** -- `@Scheduled` re-decodes mp4 links older than `link-ttl-hours`

### Schema Drift Detection

All Kodik API responses pass through `KodikResponseMapper`:
1. Response received as `Map<String, Object>` (no data loss)
2. Keys compared against known DTO fields via reflection
3. Unknown keys → `WARN` log with field names
4. Then converted to typed DTO via `ObjectMapper.convertValue()`

This ensures we never silently lose new fields from Kodik.

## Prerequisites

- **Java 21+** and **Maven 3.9+** for building
- **MySQL 8.0+** (or use Docker Compose)
- **ffmpeg** — required for `.ts` → `.mp4` remux after HLS download (`brew install ffmpeg` / `apt install ffmpeg`)
- **Chromium** — installed automatically by Playwright on first run

## Background Tasks

| Task | Interval | Description |
|------|----------|-------------|
| `refreshExpiredLinks` | `decoder.refresh-interval-ms` | Re-decodes mp4 links older than `link-ttl-hours` |
| `retryFailedDecodes` | Same interval + 30min offset | Retries variants where decode previously failed |

## Project Structure

```
src/main/java/com/orinuno/
├── client/                    # Kodik API client
│   ├── KodikApiClient.java    # 7 endpoints, raw + typed responses
│   ├── KodikResponseMapper.java # Schema drift detection
│   ├── KodikApiRateLimiter.java # Token-bucket rate limiter
│   └── dto/                   # Request/Response DTOs
├── controller/                # REST controllers
│   ├── ContentController.java
│   ├── ParseController.java
│   ├── ExportController.java
│   ├── HlsController.java    # HLS manifest API
│   └── HealthController.java
├── service/                   # Business logic
│   ├── ParserService.java     # Search, decode, TTL refresh
│   ├── KodikVideoDecoderService.java # 8-step decode + brute-force + fallback
│   ├── GeoBlockDetector.java  # CDN + API geo-block detection
│   ├── HlsManifestService.java # m3u8 URL + absolutized manifest
│   ├── ProxyWebClientService.java # Proxy-aware HTTP with fallback
│   └── ProxyProviderService.java  # Round-robin proxy pool
├── model/                     # Entities + DTOs
├── mapper/                    # Entity↔DTO converters
├── repository/                # MyBatis mapper interfaces
└── configuration/             # Spring configs
```

## Contributing

Contributions are welcome — bug reports, feature ideas, documentation
fixes, and pull requests alike.

- Short ground rules: [CONTRIBUTING.md](./CONTRIBUTING.md)
- Expected behaviour and responsible-use guidelines: [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)
- Security issues (please do **not** file a public issue): [SECURITY.md](./SECURITY.md)

Code style is enforced by `spotless-maven-plugin` (Google Java Format,
AOSP profile) and runs on `mvn verify`. Fix violations locally with:

```bash
mvn spotless:apply
```

Optional static analysis (SpotBugs, opt-in, not wired into CI):

```bash
mvn spotbugs:check
```

## License

Released under the [MIT License](./LICENSE) — Copyright (c) 2026 Vitaly Lyutarevich.

## Disclaimer

This project is published for research and educational purposes and is **not
affiliated with, endorsed by, or sponsored by** Kodik. Users are solely
responsible for complying with Kodik's Terms of Service, applicable copyright
law, and any other regulations that govern their use of the software.

Please read [DISCLAIMER.md](./DISCLAIMER.md) in full before using this project.

## Responsible use

Orinuno is intentionally shipped with **conservative defaults** — the Kodik
client sleeps `request-delay-ms: 500` between calls, the token bucket allows
only `rate-limit-per-minute: 30`, and CDN link TTL refresh is batched. These
knobs are exposed in `application.yml` so you can **tune them to your
situation**, but please do so responsibly:

- **Lower** the numbers if your use case is casual browsing, a single user,
  or a test environment — being gentler never hurts.
- **Keep the defaults** for typical integration work; they were picked to
  stay well within Kodik's public API expectations.
- **Raise the numbers only** when you have explicit approval from Kodik for
  a higher quota, a dedicated token, or when running against a fully local
  sandbox that you control. Never ramp up to abuse a shared public endpoint.

The project is **not** designed for mass scraping, public mirrors, or
commercial re-distribution of third-party video content. See
[CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md) §2 "Responsible-use guidelines"
for the full list of what we ask contributors and users not to do, and
[DISCLAIMER.md](./DISCLAIMER.md) for the legal framing.

## Takedown requests

If you are a rights holder or platform representative and you believe content
in this repository should be adjusted or removed, open a
`[takedown]`-prefixed issue at
<https://github.com/Samehadar/orinuno/issues> or contact the maintainer via
<https://lyutarevich.com/>. Reasonable requests will be handled in good faith.
