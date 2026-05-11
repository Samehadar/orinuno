---
title: Project Structure
description: Map of the source tree — where each responsibility lives and where to look first when investigating a bug.
---

Orinuno is a multi-module Maven reactor. As of ADR 0018 / 0020 (per-source
service split) the modules are:

**Deployables:**

- `orinuno-app/` — public API gateway. Controllers, the demo-facing REST
  surface, reverse-proxy to per-source services, read-only canonical
  catalog access through a Caffeine cache.
- `orinuno-source-kodik/` — standalone Kodik service. Owns the `kodik_*`
  MySQL schema, serves `/api/v1/kodik/*`, `/api/v1/embed/*`,
  `/api/v1/reference/*`, `/api/v1/source-events/*`. See
  [`docs/adr/0018-per-source-service-split-kodik.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0018-per-source-service-split-kodik.md).
- `meter/` — OSS catalog collector. Single writer of the shared
  `catalog_*` schema. Polls each per-source service's
  `/api/v1/source-events/ready`. See
  [`docs/adr/0020-oss-meter-extraction.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0020-oss-meter-extraction.md).
- *(planned)* `orinuno-source-jutsu/` — standalone JutSu service. See
  [`docs/adr/0019-per-source-service-split-jutsu.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0019-per-source-service-split-jutsu.md).

**Libraries:**

- `orinuno-source-contract/` — sealed `SourceCatalogEvent` contract
  shared with every consumer (meter, kodik-parser, future OSS
  aggregators). See
  [`docs/adr/0017-source-event-contract.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0017-source-event-contract.md).
- `kodik-sdk/` — Spring-free Kodik HTTP client + decoder + token registry +
  drift detector (absorbed the former `kodik-sdk-drift` module). See
  [`docs/adr/0001-kodik-sdk-extraction.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0001-kodik-sdk-extraction.md).
- `kodik-sdk-spring-boot-starter/` — auto-configures kodik-sdk beans
  (Kodik HTTP client, token registry, decoder metrics, drift detector,
  startup token validation `LifecycleRunner`) for any Spring Boot host.
- `jutsu-sdk/` — standalone JutSu client (DLE auth, sticky cookies, 1 RPS
  rate-limit, premium decode). See
  [`docs/adr/0012-jutsu-sdk-extraction.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0012-jutsu-sdk-extraction.md).
- `sibnet-sdk/` — standalone Sibnet decoder (`shell.php` + `player.src(...)`
  regex). Stateless. See
  [`docs/adr/0013-sibnet-and-aniboom-sdk-extraction.md`](https://github.com/Samehadar/orinuno/blob/master/docs/adr/0013-sibnet-and-aniboom-sdk-extraction.md).
- `aniboom-sdk/` — standalone Aniboom decoder (`<input id="video-data">` +
  Jackson). Stateless.

The provider SDKs (`jutsu-sdk`, `sibnet-sdk`, `aniboom-sdk`) are designed for
direct consumption by external projects — they do not pull in MySQL, Liquibase,
MyBatis, or any orinuno-specific type. `orinuno-app` wires them in through
thin `*SdkConfiguration` beans (`JutsuSdkConfiguration`,
`SibnetSdkConfiguration`, `AniboomSdkConfiguration`); since the SDK-split
Step 4 (`c09f283`) the controllers (`SourcesController`, `ProvidersController`)
inject the SDK facades (`JutsuClient`, `SibnetClient`, `AniboomClient`)
directly — no adapter `*DecoderService` shims.

```
.
├── pom.xml                            # reactor parent (packaging=pom)
├── kodik-sdk-drift/
│   ├── pom.xml                        # jackson-annotations + lombok + slf4j
│   └── src/{main,test}/java/com/kodik/sdk/drift/
├── jutsu-sdk/
│   ├── pom.xml                        # spring-webflux + reactor-netty + bucket4j
│   └── src/{main,test}/java/com/orinuno/jutsu/
├── sibnet-sdk/
│   ├── pom.xml                        # spring-webflux + reactor-netty
│   └── src/{main,test}/java/com/orinuno/sibnet/
├── aniboom-sdk/
│   ├── pom.xml                        # spring-webflux + reactor-netty + jackson-databind
│   └── src/{main,test}/java/com/orinuno/aniboom/
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

## Source trees (provider SDKs)

```
jutsu-sdk/src/main/java/com/orinuno/jutsu/
├── JutsuClient.java               # public facade
├── JutsuConfig.java               # immutable record + builder
├── JutsuDecodeResult.java
├── JutsuErrorCodes.java
├── auth/JutsuSessionManager.java  # DLE login + sticky cookies
├── decoder/JutsuDecoder.java      # premium gate + Yandex CDN extractor
├── parser/JutsuSourceParser.java  # URL-shape parser
└── ratelimit/JutsuRateLimiter.java # Bucket4j 1 RPS hard cap

sibnet-sdk/src/main/java/com/orinuno/sibnet/
├── SibnetClient.java              # decode(long videoId) + decode(String shellUrl)
├── SibnetConfig.java
├── SibnetDecodeResult.java
├── SibnetErrorCodes.java
├── decoder/SibnetDecoder.java
└── parser/SibnetSourceParser.java

aniboom-sdk/src/main/java/com/orinuno/aniboom/
├── AniboomClient.java
├── AniboomConfig.java
├── AniboomDecodeResult.java
├── AniboomErrorCodes.java
├── decoder/AniboomDecoder.java    # HTML-entity decode + Jackson
└── parser/AniboomSourceParser.java
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

jutsu-sdk/src/test/java/com/orinuno/jutsu/
├── JutsuClientTest.java
├── auth/JutsuSessionManagerTest.java
├── decoder/JutsuDecoderTest.java
├── parser/JutsuSourceParserTest.java
└── ratelimit/JutsuRateLimiterTest.java  # SDK-pure, no Spring context

sibnet-sdk/src/test/java/com/orinuno/sibnet/
├── SibnetClientTest.java
├── decoder/SibnetDecoderTest.java
└── parser/SibnetSourceParserTest.java

aniboom-sdk/src/test/java/com/orinuno/aniboom/
├── AniboomClientTest.java
├── decoder/AniboomDecoderTest.java
└── parser/AniboomSourceParserTest.java
```

Run any single SDK suite without booting the orinuno-app context:

```sh
mvn -pl jutsu-sdk test
mvn -pl sibnet-sdk test
mvn -pl aniboom-sdk test
mvn -pl kodik-sdk-drift test
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
