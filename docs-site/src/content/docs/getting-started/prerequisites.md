---
title: Prerequisites
description: Runtime and build-time requirements for running Orinuno locally or in production.
---

Orinuno is a Spring Boot service written in Java 21. It ships with a small
ffmpeg dependency and a headless Chromium (installed by Playwright on first
run).

## Runtime

| Dependency | Version | Purpose |
| --- | --- | --- |
| **Java** | 21+ | Runtime for the service |
| **MySQL** | 8.0+ | Metadata store; schema is Liquibase-managed |
| **ffmpeg** | any recent | `.ts` → `.mp4` remux after HLS download (`brew install ffmpeg` / `apt install ffmpeg`) |
| **Chromium** | installed automatically | Headless browser used by the Playwright video fetcher |
| **Kodik API token** | — | Required to talk to `kodik-api.com` |

## Build-time

- **Maven** 3.9+ to compile and run tests.
- **Docker** — recommended for Testcontainers-based integration tests, and
  for the bundled Compose stack.

## Why these specific pieces?

- **Java 21** — the codebase uses records, pattern-matching `switch`,
  virtual threads where it makes sense, and the stable `java.net.http`
  client for HLS segment download. Earlier JDKs do not work.
- **MySQL 8.0** — Liquibase changelogs assume `InnoDB` and
  `utf8mb4_unicode_ci`. MariaDB may work but is not tested.
- **ffmpeg** — the CDN often serves `.ts` HLS segments. Browsers cannot
  play MPEG-TS natively, so we remux with `ffmpeg -c copy` (stream copy,
  instant — no re-encoding).
- **Chromium via Playwright** — direct HTTP clients are blocked by the CDN
  even with correct headers. See [Video download](/orinuno/architecture/video-download/)
  for the full research log.

## Next steps

- [Quick Start](/orinuno/getting-started/quick-start/) — run the stack.
- [Configuration](/orinuno/getting-started/configuration/) — tune the knobs.
