# ADR 0002 — Kodik HTTP method policy: stay on POST, document GET as fallback

- **Status**: Accepted
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → API-3, [docs/quirks-and-hacks.md](../quirks-and-hacks.md), [docs/research/2026-05-02-api-and-decoder-probe.md](../research/2026-05-02-api-and-decoder-probe.md)

## Context

`KodikApiClient` issues every Kodik request as **POST with parameters in the query string**. This matches three of the four reference clients we follow (kodik-api Rust, kodikwrapper TS, AnimeParsers Python). Only KodikDownloader (Java/Android) uses GET.

API-3 in the backlog asked: should we switch any endpoints to GET? Reasoning given: cacheability (CDN/HTTP-cache friendliness), bandwidth (no POST envelope overhead), conformance with REST conventions (these are read-only).

A live probe on 2026-05-02 (full results in [docs/research/2026-05-02-api-and-decoder-probe.md](../research/2026-05-02-api-and-decoder-probe.md), summary in [quirks-and-hacks.md](../quirks-and-hacks.md)) showed that **all 7 public endpoints accept both methods and return byte-identical JSON bodies** modulo the per-request `time` field.

## Decision

**Stay on POST in `KodikApiClient`. Do not switch any endpoint to GET.**

Reasons:

1. **Asymmetric risk.** All three clients we mirror (kodik-api, kodikwrapper, AnimeParsers) use POST. If Kodik ever decides to lock down GET (because it's effectively undocumented), we would be in the smaller user pool. Inverse is not true: Kodik can never realistically lock down POST without breaking its own embedded player.

2. **No real win.** orinuno does not benefit from CDN cacheability (we are the cache layer) and the POST overhead is irrelevant compared to upstream Kodik latency.

3. **Schema drift parity.** Our schema-drift detector and stability tests run against POST. Switching method classes breaks the mental model "drift = upstream changed", because we'd need to also rule out method-related differences.

## Allowed exception: `KodikListProxyService` GET fallback

`KodikListProxyService` proxies `/list` calls to a downstream consumer (parser-kodik). If a consumer ever insists on GET (because their HTTP layer lacks POST middleware, or their auditing pipeline only accepts idempotent verbs), we MAY add a GET-pass-through MODE to `KodikListProxyService` only.

This MUST be opt-in via configuration (`orinuno.kodik.list.method=POST|GET`, default `POST`), MUST NOT change `KodikApiClient`, and MUST be covered by a stability test that asserts byte-identical bodies for both methods.

## Trade-offs

**Cost of NOT switching**: a small idiomatic-REST gripe in code review. Acceptable.

**Cost of switching**: above-mentioned asymmetric risk + need to re-run the entire stability suite under both methods + drift-detector confusion. Not acceptable for an unmeasurable benefit.

## Verification

- [`KodikHttpMethodProbeTest`](../../orinuno-app/src/test/java/com/orinuno/client/KodikHttpMethodProbeTest.java) — parameterized live test that re-runs the GET-vs-POST probe across all 7 endpoints whenever `KODIK_TOKEN` is set. Fails the build if Kodik ever introduces method asymmetry, alerting us to revisit this ADR.
- The probe results live in `docs/research/2026-05-02-api-and-decoder-probe.md` and are dated; rerun the test (or the bash one-liner in that doc) to re-verify.
