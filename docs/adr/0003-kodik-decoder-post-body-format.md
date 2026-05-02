# ADR 0003 — Decoder POST body must be `application/x-www-form-urlencoded` with string-typed booleans

- **Status**: Accepted
- **Date**: 2026-05-02
- **Deciders**: orinuno maintainers
- **Related**: BACKLOG.md → API-6, [docs/quirks-and-hacks.md](../quirks-and-hacks.md), [docs/research/2026-05-02-api-and-decoder-probe.md](../research/2026-05-02-api-and-decoder-probe.md)

## Context

API-6 in the backlog asked: should `KodikVideoDecoderService.sendVideoRequest()` migrate from `MultiValueMap<String,String>` (form-encoded) to a typed Java DTO + JSON body? Reasoning given: type safety, easier mocking in tests, eliminate string `"true"` / `"false"` magic, parity with our other DTOs.

A live probe on 2026-05-02 (from a Kazakhstan IP — Kodik is more permissive for CIS than UK; see geo note in [docs/quirks-and-hacks.md](../quirks-and-hacks.md)) confirmed that Kodik's video-info endpoints (`/ftor`, `/kor`, `/gvi`, `/seria`) ARE NOT JSON-aware:

| Request                                                                                          | Status | Body                                                  |
|--------------------------------------------------------------------------------------------------|--------|-------------------------------------------------------|
| `Content-Type: application/x-www-form-urlencoded` + form body with **real iframe urlParams**     | 200    | `{"links": {...}, "default": 360, ...}`               |
| `Content-Type: application/x-www-form-urlencoded` + form body with **fake/missing urlParams**    | 500    | HTML error page (router accepted, app rejected)       |
| `Content-Type: application/x-www-form-urlencoded` + valid form body but iframe `urlParams` had a token-style value `{"token":...}` | 500    | `{"error":"Отсутствует или неверный токен"}` (JSON)   |
| `Content-Type: application/json` + ANY body                                                       | 404    | HTML 404 page — **router rejects JSON at routing level, never reaches app code** |
| no `Content-Type` header at all                                                                   | 404    | HTML 404 page                                         |

Two layers of rejection:

1. **Router-level**: anything that isn't `application/x-www-form-urlencoded` returns 404. This is good — there's no chance of accidental JSON parsing.
2. **App-level**: form-encoded bodies are parsed; missing/invalid params produce 500 (HTML error page if the body is incomplete, JSON `{"error":...}` body if the body is shaped enough that Kodik's app code identifies it as a `/ftor` call).

The misleading 500-with-`{"error":"missing token"}` we saw in earlier exploration was the special case where the form-encoded body was structurally valid but the embedded `d_sign`/`pd_sign` were stale / fabricated. **JSON never reaches that code path.**

Furthermore, the `bad_user` and `cdn_is_working` flags MUST be the **string** literals `"false"` / `"true"`. Sending raw JSON booleans (`false` / `true` un-quoted) is what would happen if we used a JSON body — and as the table above shows, Kodik wouldn't even reach the parsing step.

## Decision

**Keep `application/x-www-form-urlencoded` form encoding for the decoder POST. Document the constraint loudly.**

Specifically:

1. `KodikVideoDecoderService.sendVideoRequest()` keeps `MultiValueMap<String,String>` + `BodyInserters.fromFormData()`.
2. Boolean flags are **string** values: `formData.add("bad_user", "false")`, `formData.add("cdn_is_working", "true")` — never `Boolean.toString(false)` (same effect, more "look-natural" temptation), never JSON booleans.
3. A Javadoc comment on `sendVideoRequest` and an inline comment near the `bad_user`/`cdn_is_working` lines references this ADR.
4. The unit test `KodikVideoDecoderServiceTest` continues to assert body shape via captured `BodyInserter`.

## Future migration option (rejected for now)

A typed Java DTO with custom serialization (string booleans, form encoding) is technically possible but adds a serialization layer for a request shape that has 6 fields and changes once a year. Not worth it.

If Kodik ever introduces a JSON-aware video-info endpoint in the future (no signs of this in 2026-05), revisit this ADR.

## Trade-offs

**Cost of NOT migrating**: the magic strings `"true"` / `"false"` and the `MultiValueMap` boilerplate. ~6 lines of code per call site. Acceptable.

**Cost of migrating**: one wrong commit and the entire decoder is broken with a misleading error. The probe history doc + this ADR + the regression test exist precisely to prevent that. The migration itself would not pay back.

## Verification

- [`KodikVideoDecoderServiceTest`](../../orinuno-app/src/test/java/com/orinuno/service/KodikVideoDecoderServiceTest.java) — asserts form-encoded body shape and string-typed booleans.
- [`KodikDecoderRawProbeTest`](../../orinuno-app/src/test/java/com/orinuno/service/KodikDecoderRawProbeTest.java) (new in this commit) — when `KODIK_TOKEN` is set, sends both form-body and JSON-body requests directly to `/ftor` and asserts the documented status codes. Fails the build if Kodik ever starts accepting JSON.
