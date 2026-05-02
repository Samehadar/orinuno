# 2026-05-02 — Kodik API method probe + decoder structure probe

This document is the **raw findings** from a research probe run on 2026-05-02 against `kodik-api.com` (REST) and `kodikplayer.com` (decoder pipeline). It feeds three ADRs:

- ADR 0002 — HTTP method policy (POST stays default).
- ADR 0003 — Decoder POST body must be `application/x-www-form-urlencoded`.
- ADR 0004 — Decoder quality strategy.

It also exposed one production bug: the `PLAYER_JS_PATTERN` regex is too narrow for the 2026-05 player-js naming scheme. Fixed in the same commit batch (see `KodikVideoDecoderRegexTest`).

## Method

Live `curl` probes from a developer machine. Token: one of the 4 active unstable tokens in `data/kodik_tokens.json` (rotated for each subsection so we don't burn rate limit on a single token). User-Agent: `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36` (matches what the production decoder sends today).

**Geo**: probe ran from a Kazakhstan exit (`109.248.32.206`, Karagandy). The first attempt was from a UK exit and produced misleading data (decoder returned `links` populated with dummy "geo-block" URLs). See `docs/quirks-and-hacks.md` "Kodik is geo-fenced" entry — Kodik's CDN refuses to issue real URLs to non-CIS callers. Always re-run from CIS before drawing conclusions.

For each endpoint we recorded:

- HTTP status (GET vs POST)
- Response body shape
- Per-endpoint quirks

For the decoder pipeline, we additionally walked the full 8 steps for 3 different iframe types (movie, anime serial, russian movie) and 10 different titles for the quality probe.

## API method probe (full results)

### With valid token, all 7 endpoints

```
Endpoint                  |  GET | POST | bodies-match (modulo "time" field)
--------------------------|------|------|-----------------------------------
/search?title=naruto      |  200 |  200 | YES
/list                     |  200 |  200 | YES
/translations/v2          |  200 |  200 | YES
/genres                   |  200 |  200 | YES
/countries                |  200 |  200 | YES
/years                    |  200 |  200 | YES
/qualities/v2             |  200 |  200 | YES
```

### Edge cases

| Case                                          | Result                                                  |
|-----------------------------------------------|---------------------------------------------------------|
| Without token (any method)                    | `500 + {"error":"Отсутствует или неверный токен"}`       |
| `/search` with no filter param (any method)   | `500 + {"error":"Не указан хотя бы один параметр для поиска"}` |
| `/search?types=film`                          | `500 + {"error":"Неправильный тип"}` (only `russian-movie`/`foreign-movie`/`russian-cartoon`/`foreign-cartoon`/`anime-serial`/`foreign-serial`/`russian-serial`/`soviet-cartoon` are valid types) |
| `OPTIONS /search`                             | `500` (no real CORS handling)                           |
| `HEAD /search`                                | `500`                                                   |
| Wrong domain `kodikapi.com` (no hyphen)       | DNS resolves elsewhere — DO NOT use                     |

### Body format probes (for `/search`)

| Body shape                                                                | Status |
|---------------------------------------------------------------------------|--------|
| Query string + `POST` + empty body                                        | 200    |
| Query string + `GET`                                                      | 200    |
| Empty query + `POST application/x-www-form-urlencoded` body with `title=…`| 200    |
| Empty query + `POST application/json` body with `{"title":"…"}`            | 500 ("missing or invalid token" — misleading) |

→ Kodik **does not parse JSON request bodies** on `/search` either. Confirmed for `/ftor` decoder endpoint as well.

## Decoder pipeline probe

### Iframe HTML changes (vs codebase assumption)

For 4 iframe variants we extracted the player-JS path:

| iframe URL                                          | type    | player-js found in HTML                                                  | regex `app\.player_[^.]+\.js` matches? |
|-----------------------------------------------------|---------|--------------------------------------------------------------------------|----------------------------------------|
| `/serial/6646/…/720p` (Naruto)                      | `seria` | `assets/js/app.serial.b0696c…62.js`                                       | NO                                     |
| `/serial/10015/…/720p` (FMA)                        | `seria` | `assets/js/app.serial.b0696c…62.js` (same file across serials!)           | NO                                     |
| `/video/46929/…/720p` (foreign-movie)               | `video` | `assets/js/app.player_single.a037e9…b0.js`                                | YES (group 1 = `single`)               |
| `/video/62336/…/720p` (russian-movie)               | `video` | `assets/js/app.player_single.a037e9…b0.js` (same file across movies!)     | YES                                    |
| `/video/28131/…/720p` (foreign-cartoon)             | `video` | `assets/js/app.player_single.a037e9…b0.js`                                | YES                                    |

**Both** player-js files contain the same `type:"POST",url:atob("L2Z0b3I=")` snippet → POST URL = `/ftor` for everything observed today.

→ Bug: `KodikVideoDecoderService.PLAYER_JS_PATTERN` regex `src="/(assets/js/app\.player_[^"]+\.js)"` returns `null` on `seria`-type iframes, decoder errors with `"Player JS path not found in iframe"`. The `cachedVideoInfoPath` safety net never gets hit because the error is raised earlier in the chain. **All serial decodes fail today**.

→ Fix is one-line regex broadening + new regression test `KodikVideoDecoderRegexTest`.

### `/ftor` response shape (one consistent JSON for all iframe types)

Top-level keys: `advert_script`, `default`, `domain`, `ip`, `links`, `vast`, `reserve_vast`.

Specifically:

- `advert_script` (string) — usually empty.
- `domain` (string) — `kodik.cc` for all observations.
- `default` (number) — `360` for ALL 10 titles probed. NOT a "best quality" hint.
- `links` (object) — keys are quality strings (`"240"`, `"360"`, `"480"`, `"720"`), values are `[{"src": "<rot13+b64-encoded URL>", "type": "application/x-mpegURL"}]`.
- `vast` (array) — VAST ad-tag entries.
- `reserve_vast` (array, ~21 entries) — fallback VAST tags.
- `ip` (string) — caller's IP (echoed for debug).

### Quality distribution probe (10 titles)

| Title                          | Qualities                | `default` |
|--------------------------------|--------------------------|-----------|
| Наруто [ТВ-1] (anime-serial)   | `240, 360, 480, 720`     | 360       |
| Стальной алхимик [ТВ-2]        | `360, 480, 720`          | 360       |
| Атака титанов [ТВ-1]           | `360, 480, 720`          | 360       |
| Ван-Пис [ТВ]                   | `360, 480, 720`          | 360       |
| Врата Штейна [ТВ]              | `360, 480, 720`          | 360       |
| Ниндзя-мстители (anime-movie)  | `240, 360, 480, 720`     | 360       |
| Армитаж: Полиматрица           | `240, 360, 480, 720`     | 360       |
| Интерстелла 5555                | `360, 480, 720`          | 360       |
| Джокер (foreign-movie)         | `240, 360, 480, 720`     | 360       |
| (10th query returned no result, see live shell log) | n/a                      | n/a       |

Aggregate: `360, 480, 720` always present. `240` for ~half. `1080`/`2160` never observed.

### `/ftor` body-format probe (KZ IP)

| Body                                                                                          | Status | Body kind                                                                                  |
|-----------------------------------------------------------------------------------------------|--------|--------------------------------------------------------------------------------------------|
| `application/x-www-form-urlencoded` + body with REAL urlParams (signed `d_sign`/`pd_sign` from a fresh iframe load) | 200    | JSON `{"links":..., "default":...}`                                                        |
| `application/x-www-form-urlencoded` + body with FAKE / missing signed params                  | 500    | HTML error page                                                                            |
| `application/x-www-form-urlencoded` + body where the form keys exist but signed params are stale | 500 | JSON `{"error":"Отсутствует или неверный токен"}` (misleading — it's the signature, not the token) |
| `application/json` + ANY body (with or without Referer / X-Requested-With / token)             | 404    | HTML 404 page — **router-level rejection, never reaches app code**                          |
| Missing `Content-Type` header                                                                  | 404    | HTML 404 page                                                                              |

**Conclusion**: `/ftor` discriminates Content-Type at the router. The previous claim "JSON returns 500 with misleading token error" is wrong; the 500-with-JSON-token-error is reserved for form-encoded bodies with stale `d_sign`. JSON never gets that far.

## Reproducing this probe

A bash script that reproduces every table in this document is checked in at `scripts/research/2026-05-02-api-and-decoder-probe.sh`. Set `KODIK_TOKEN` (any of the 4 active tokens in `data/kodik_tokens.json`) and run it.

Or use the JUnit version: [`KodikHttpMethodProbeTest`](../../orinuno-app/src/test/java/com/orinuno/client/KodikHttpMethodProbeTest.java) and [`KodikDecoderRawProbeTest`](../../orinuno-app/src/test/java/com/orinuno/service/KodikDecoderRawProbeTest.java) (both `@EnabledIfEnvironmentVariable("KODIK_TOKEN")`).

## Followups created

- `fix(decoder): handle Kodik 2026-05 split player JS naming` — same commit batch.
- `feat(decoder): orinuno_decoder_quality_picked_total Prometheus counter` — DECODE-6 (next phase).
- `docs/quirks-and-hacks.md` — populated with 6 entries from this probe.
- `docs/adr/0002-…0004-…md` — three ADRs.
