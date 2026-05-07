---
title: Architecture Overview
description: High-level map of Orinuno — context, containers, components, and how data flows through the system.
---

Orinuno is a single-process Spring Boot WebFlux service that fronts the public
Kodik API, decodes obfuscated video URLs, stores metadata in MySQL, and
exposes a versioned REST surface for consumers. The diagrams on this page
cover three levels: system context, internal components, and the PlantUML
container view.

The codebase is a multi-module Maven reactor — `orinuno-app` (the Spring Boot
service) plus four standalone SDK modules (`kodik-sdk-drift`, `jutsu-sdk`,
`sibnet-sdk`, `aniboom-sdk`). The SDKs are reusable outside this repo and
have no dependency on Spring Boot, MySQL, or any orinuno-specific type. See
[Project Structure](/orinuno/development/project-structure/) for the file-tree
view and the [CHANGELOG](https://github.com/Samehadar/orinuno/blob/master/CHANGELOG.md)
`SDK-SPLIT 2026-05-03` entry for the migration history.

## System context (C4)

```mermaid
C4Context
    title Orinuno — System Context

    Person(consumer, "Consumer", "Any service or person that needs parsed Kodik content")
    System(orinuno, "Orinuno", "Standalone service. Parses, decodes, exports Kodik video content")
    System_Ext(kodik_api, "Kodik API", "kodik-api.com — search anime/films/serials")
    System_Ext(kodik_cdn, "Kodik CDN", "solodcdn.com — hosts obfuscated video files")
    SystemDb(mysql, "MySQL", "Parsed content, variants, proxy pool")

    Rel(consumer, orinuno, "REST API", "/api/v1/*")
    Rel(orinuno, kodik_api, "Search content", "HTTPS POST")
    Rel(orinuno, kodik_cdn, "Decode and download", "HTTPS GET/POST")
    Rel(orinuno, mysql, "Read/Write", "JDBC")
```

## Component diagram

```mermaid
graph TB
    subgraph Controllers
        PC[ParseController]
        CC[ContentController]
        EC[ExportController]
        HC[HealthController]
        DLC[DownloadController]
        SC[StreamController]
    end

    subgraph Services
        PS[ParserService]
        CS[ContentService]
        EDS[ExportDataService]
        KVDS[KodikVideoDecoderService]
        DHT[DecoderHealthTracker]
        PPS[ProxyProviderService]
        VDS[VideoDownloadService]
        PVF[PlaywrightVideoFetcher]
    end

    subgraph Clients
        KAC[KodikApiClient]
        KPW[kodikPlayerWebClient]
        CDNWC[kodikCdnWebClient]
    end

    subgraph Repositories
        CR[(ContentRepository)]
        EVR[(EpisodeVariantRepository)]
        PR[(ProxyRepository)]
    end

    subgraph External
        KAPI[kodik-api.com]
        KCDN[Kodik CDN]
        DB[(MySQL)]
    end

    subgraph Storage
        FS[Local FS]
    end

    PC --> PS
    CC --> CS
    EC --> EDS
    HC --> DHT
    HC --> PPS
    DLC --> VDS
    SC --> VDS
    SC --> PVF

    PS --> KAC
    PS --> CS
    PS --> KVDS
    PS --> EVR

    CS --> CR
    CS --> EVR
    EDS --> CR
    EDS --> EVR

    VDS --> PVF
    VDS --> KVDS
    VDS --> CDNWC
    VDS --> EVR
    VDS --> FS

    KVDS --> KPW
    KVDS --> DHT
    PPS --> PR

    KAC --> KAPI
    KPW --> KCDN
    CDNWC --> KCDN

    CR --> DB
    EVR --> DB
    PR --> DB
```

## Container view (PlantUML)

Rendered from [`docs/0_architecture_overview.puml`](https://github.com/Samehadar/orinuno/blob/master/docs/0_architecture_overview.puml)
by the repository's PlantUML workflow.

![Architecture overview](/orinuno/diagrams/0_architecture_overview.svg)

## Key flows

1. **Search and parse** — `POST /api/v1/parse/search` → Kodik API (`/search` with up to 70 filters) → save content, variants, and raw `material_data` to MySQL. See [Kodik API flow](/orinuno/architecture/kodik-api-flow/).
2. **Decode** — `POST /api/v1/parse/decode/{id}` decodes every variant of a content item; `POST /api/v1/parse/decode/variant/{variantId}` decodes a single variant (used by the demo UI's per-row "Decode" button). Both fetch the player iframe via the proxy pool → extract JS params → resolve the video-info endpoint with a fallback chain → brute-force ROT decode → store `mp4_link`. See [Video decoding](/orinuno/architecture/video-decoding/).
3. **HLS manifest** — `GET /api/v1/hls/{id}/manifest` → fresh decode → fetch m3u8 → absolutize URLs → return playlist. See [HLS manifest](/orinuno/architecture/hls-manifest/).
4. **Export** — `GET /api/v1/export/{id}` → structured JSON grouped by season → episode → variant. Schema is stable and intended for downstream consumers.
5. **TTL refresh** — `@Scheduled` re-decodes mp4 links older than `link-ttl-hours`. See [TTL refresh](/orinuno/operations/ttl-refresh/).
6. **jut.su L1 catalog cache (ADR 0016 P1a)** — `JutsuCatalogSyncService` keeps `jutsu_title` / `jutsu_episode` in sync with upstream: full crawl every `JUTSU_SYNC_FULL_CRAWL_INTERVAL_HOURS` (default 48h) plus an incremental notice walk every `JUTSU_SYNC_NOTICE_INTERVAL_MINUTES` (default 5m). The notice walk takes a transactional lock through `JutsuNoticeLockService` (atomic acquire-or-recover, recovers crashed workers after `JUTSU_SYNC_NOTICE_LOCK_TTL_MINUTES`). `JutsuApiController` reads `/catalog`, `/search`, `/anime/{slug}`, `/episode` DB-first; cache misses (and explicit `?refresh=true`) go through `JutsuLiveFallbackService` — Bucket4j RPS rate limit, Caffeine negative cache (only on 404/410/null upstream — 5xx/IO surface as 502 without poisoning the cache), and a kill-switch. Successful live calls are upserted into the L1 tables. Every response carries `X-Sync-Stale-Seconds` from `JutsuStalenessTracker`. See [Sources & Multi-Provider API](/orinuno/api/sources/).

## Related

- [Kodik API flow](/orinuno/architecture/kodik-api-flow/)
- [Video decoding](/orinuno/architecture/video-decoding/)
- [Schema drift](/orinuno/architecture/schema-drift/)
- [Database](/orinuno/architecture/database/)
