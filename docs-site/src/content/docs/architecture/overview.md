---
title: Architecture Overview
description: High-level map of Orinuno — context, containers, components, and how data flows through the system.
---

Orinuno is a single-process Spring Boot WebFlux service that fronts the public
Kodik API, decodes obfuscated video URLs, stores metadata in MySQL, and
exposes a versioned REST surface for consumers. The diagrams on this page
cover three levels: system context, internal components, and the PlantUML
container view.

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

## Related

- [Kodik API flow](/orinuno/architecture/kodik-api-flow/)
- [Video decoding](/orinuno/architecture/video-decoding/)
- [Schema drift](/orinuno/architecture/schema-drift/)
- [Database](/orinuno/architecture/database/)
