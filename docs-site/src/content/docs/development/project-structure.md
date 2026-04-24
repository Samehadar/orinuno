---
title: Project Structure
description: Map of the source tree — where each responsibility lives and where to look first when investigating a bug.
---

Orinuno follows the standard Spring Boot layout with three package
conventions: `controller`, `service`, `repository` for the core domain, and
`client`, `mapper`, `model` for cross-cutting concerns.

## Source tree

```
src/main/java/com/orinuno/
├── client/                    # Kodik API client
│   ├── KodikApiClient.java    # 7 endpoints, raw + typed responses
│   ├── KodikResponseMapper.java  # Schema drift detection
│   ├── KodikApiRateLimiter.java  # Token-bucket rate limiter
│   └── dto/                   # Request/Response DTOs
├── controller/                # REST controllers
│   ├── ContentController.java
│   ├── ParseController.java
│   ├── ExportController.java
│   ├── HlsController.java
│   ├── DownloadController.java
│   ├── StreamController.java
│   └── HealthController.java
├── service/                   # Business logic
│   ├── ParserService.java     # Search, decode, TTL refresh
│   ├── KodikVideoDecoderService.java  # 8-step decode + brute-force
│   ├── GeoBlockDetector.java  # CDN + API geo-block detection
│   ├── HlsManifestService.java  # m3u8 URL + absolutized manifest
│   ├── ProxyWebClientService.java  # Proxy-aware HTTP with fallback
│   ├── ProxyProviderService.java   # Round-robin proxy pool
│   ├── VideoDownloadService.java   # Orchestrates Playwright downloads
│   └── PlaywrightVideoFetcher.java # Headless Chromium + HLS parallel
├── model/                     # Entities + DTOs
├── mapper/                    # Entity ↔ DTO converters
├── repository/                # MyBatis mapper interfaces
└── configuration/             # Spring configs, properties, filters
```

## Resources

```
src/main/resources/
├── application.yml
└── com/orinuno/db/
    ├── mapper/                # MyBatis XML with resultMaps and SQL
    └── changelog/
        ├── liquibase-changelog.yaml
        └── scripts/           # *.sql migrations
```

## Tests

```
src/test/java/com/orinuno/
├── *Test.java                          # unit tests
├── KodikLiveIntegrationTest.java       # live Kodik API, needs KODIK_TOKEN
├── KodikApiStabilityTest.java          # 37 schema-drift assertions
└── VideoDownloadLiveIntegrationTest.java
```

## Where to start when something breaks

| Symptom | First place to look |
| --- | --- |
| 4xx/5xx on a REST call | The controller in `controller/` |
| Wrong or missing field in a response | `mapper/` + the DTO in `model/dto/` |
| Decoding fails | `KodikVideoDecoderService`, then the iframe HTML |
| Download returns 0 bytes | `PlaywrightVideoFetcher` + Playwright logs |
| Schema drift noise | `GET /api/v1/health/schema-drift` |

## Related

- [Architecture overview](/orinuno/architecture/overview/)
- [Testing](/orinuno/development/testing/)
