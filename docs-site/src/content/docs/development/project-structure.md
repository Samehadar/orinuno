---
title: Project Structure
description: Map of the source tree — where each responsibility lives and where to look first when investigating a bug.
---

Orinuno is a multi-module Maven reactor (introduced in PR3 of the
transparency roadmap). Two modules live side-by-side:

- `orinuno-app/` — the Spring Boot service: controllers, MyBatis,
  Liquibase, the demo UI, all REST/HTTP surface.
- `kodik-sdk-drift/` — the first piece of the future Kodik SDK: a
  Spring-free, domain-neutral schema-drift detector. See
  [`docs/adr/0001-kodik-sdk-extraction.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0001-kodik-sdk-extraction.md)
  for the longer-term split plan.

```
.
├── pom.xml                            # reactor parent (packaging=pom)
├── kodik-sdk-drift/
│   ├── pom.xml                        # Spring-free, jackson-annotations + lombok + slf4j
│   └── src/{main,test}/java/com/kodik/sdk/drift/
└── orinuno-app/
    ├── pom.xml                        # Spring Boot parent
    ├── spotbugs-exclude.xml
    └── src/{main,test}/...
```

## Source tree (orinuno-app)

```
orinuno-app/src/main/java/com/orinuno/
├── client/                    # Kodik API client
│   ├── KodikApiClient.java    # 7 endpoints, raw + typed responses
│   ├── KodikResponseMapper.java  # Schema drift detection (uses kodik-sdk-drift)
│   ├── KodikApiRateLimiter.java  # Token-bucket rate limiter
│   └── dto/                   # Request/Response DTOs
├── controller/                # REST controllers
│   ├── ContentController.java
│   ├── ParseController.java
│   ├── ParseRequestController.java
│   ├── ExportController.java
│   ├── HlsController.java
│   ├── DownloadController.java
│   ├── StreamController.java
│   ├── KodikEmbedController.java
│   └── HealthController.java
├── service/                   # Business logic
│   ├── ParserService.java     # Search, decode, TTL refresh
│   ├── KodikVideoDecoderService.java  # 8-step decode + brute-force
│   ├── GeoBlockDetector.java  # CDN + API geo-block detection
│   ├── HlsManifestService.java  # m3u8 URL + absolutized manifest
│   ├── ProxyWebClientService.java  # Proxy-aware HTTP with fallback
│   ├── ProxyProviderService.java   # Round-robin proxy pool
│   ├── VideoDownloadService.java   # Orchestrates Playwright downloads
│   ├── PlaywrightVideoFetcher.java # Headless Chromium + HLS parallel
│   └── KodikEmbedService.java # /get-player iframe URL shortcut
├── model/                     # Entities + DTOs
├── mapper/                    # Entity ↔ DTO converters
├── repository/                # MyBatis mapper interfaces
└── configuration/             # Spring configs, properties, filters
                                # ParseInboundRateLimitFilter (Bucket4j)
                                # ApiKeyAuthFilter
```

## Source tree (kodik-sdk-drift)

```
kodik-sdk-drift/src/main/java/com/kodik/sdk/drift/
├── DriftDetector.java
├── DriftRecord.java
├── DriftSamplingProperties.java
├── DtoFieldExtractor.java
└── ItemSamplingMode.java
```

## Resources (orinuno-app)

```
orinuno-app/src/main/resources/
├── application.yml
└── com/orinuno/db/
    ├── mapper/                # MyBatis XML with resultMaps and SQL
    └── changelog/
        ├── liquibase-changelog.yaml
        └── scripts/           # *.sql migrations
```

## Tests

```
orinuno-app/src/test/java/com/orinuno/
├── *Test.java                          # unit tests
├── KodikLiveIntegrationTest.java       # live Kodik API, needs KODIK_TOKEN
├── KodikApiStabilityTest.java          # 37 schema-drift assertions
└── VideoDownloadLiveIntegrationTest.java

kodik-sdk-drift/src/test/java/com/kodik/sdk/drift/
└── DriftDetectorTest.java              # generic drift detector tests
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
