# ADR 0013 — Sibnet & Aniboom SDK extraction (`sibnet-sdk` + `aniboom-sdk` standalone modules)

- **Status**: Accepted
- **Date**: 2026-05-03
- **Deciders**: orinuno maintainers
- **Related**: ADR 0001 (Kodik SDK extraction pilot), ADR 0006 (Sibnet + Aniboom integration), ADR 0012 (JutSu SDK extraction), Step 3 of the API/module split roadmap (per-provider `*-sdk` modules)

## Context

Step 2 (ADR 0012) extracted the most complex provider — JutSu, with its DLE auth, sticky cookie jar and Bucket4j rate limiter — into a standalone `jutsu-sdk` module. The pilot landed cleanly: 36 SDK-pure tests in 2s, one wiring smoke test in orinuno-app, no behaviour change in production.

Step 3 applies the same template to the two remaining stateless providers, **Sibnet** and **Aniboom**. Both are far simpler than JutSu — no auth, no rate limit, no session management — so the per-provider extraction lets us prove that the M3 (per-provider standalone SDK) layout scales down to "just a regex extractor + WebClient" without ceremony.

This is also the last step before the controllers can be rewired against the SDK facades directly (Step 4) and the docs/release notes can ship (Step 5). After ADR 0013 every video provider lives in its own module; only the Kodik client stays inside `orinuno-app` because (a) it is the catalog backbone with deep DB coupling and (b) the existing `kodik-sdk-drift` module already owns the only Kodik-related extraction worth doing — see ADR 0001's "Follow-ups" section.

## Decision

Create two new Maven modules `sibnet-sdk/` and `aniboom-sdk/` under the existing reactor build:

```
orinuno-parent (reactor)
├── kodik-sdk-drift     (PR3 pilot — schema-drift detector)
├── jutsu-sdk           (Step 2 — ADR 0012)
├── sibnet-sdk          ← THIS ADR
├── aniboom-sdk         ← THIS ADR
└── orinuno-app         (Spring Boot service)
```

### `sibnet-sdk` module shape

| File | What lives here |
|------|----------------|
| `com.orinuno.sibnet.SibnetClient` | Public facade — `decode(videoId)` and `decode(shellUrl)` overloads, returns `Mono<SibnetDecodeResult>`. |
| `com.orinuno.sibnet.SibnetConfig` | Immutable record + builder. Defaults: `https://video.sibnet.ru` base + `https://video.sibnet.ru/` Referer (anti-hotlink) + a real desktop UA. |
| `com.orinuno.sibnet.SibnetDecodeResult` | Result type. **Duplicate** of orinuno-app's `ProviderDecodeResult` — see "Duplication tax" in ADR 0012. |
| `com.orinuno.sibnet.SibnetErrorCodes` | Stable string constants: `SIBNET_FETCH_ERROR`, `SIBNET_PLAYER_REGEX_BREAK`, `SIBNET_INVALID_SRC`, `SIBNET_VIDEO_NOT_FOUND`. |
| `com.orinuno.sibnet.decoder.SibnetDecoder` | `shell.php` iframe HTML fetcher + `player.src([{src:…}])` regex extractor + `absolutize` URL resolver. 404 → `SIBNET_VIDEO_NOT_FOUND` (permanent — do not retry). |
| `com.orinuno.sibnet.parser.SibnetSourceParser` | Pure URL-shape parser — accepts both `/video<id>-<slug>.html` and `/shell.php?videoid=<id>` shapes, canonicalises to the iframe form. |

### `aniboom-sdk` module shape

| File | What lives here |
|------|----------------|
| `com.orinuno.aniboom.AniboomClient` | Public facade — `decode(embedUrl) → Mono<AniboomDecodeResult>`. |
| `com.orinuno.aniboom.AniboomConfig` | Immutable record + builder. Defaults: `https://aniboom.one` base + `https://animego.org/` Referer (Aniboom accepts the animego.org first-party Referer but rejects empty). |
| `com.orinuno.aniboom.AniboomDecodeResult` | Result type — duplicate of `ProviderDecodeResult`. |
| `com.orinuno.aniboom.AniboomErrorCodes` | Stable codes: `ANIBOOM_FETCH_ERROR`, `ANIBOOM_DATA_INPUT_MISSING`, `ANIBOOM_GEO_BLOCKED` (empty `data-parameters="{}"` → almost always non-CIS egress), `ANIBOOM_JSON_PARSE_ERROR`, `ANIBOOM_NO_PLAYLIST`. |
| `com.orinuno.aniboom.decoder.AniboomDecoder` | Embed-page fetcher + `<input id="video-data" data-parameters="…">` extractor + HTML-entity decoder + JSON parser. Returns HLS as `auto` and DASH as `dash`. |
| `com.orinuno.aniboom.parser.AniboomSourceParser` | Pure URL-shape parser — recognises `aniboom.one/embed/<id>`. |

### orinuno-app changes

- `SibnetSdkConfiguration` and `AniboomSdkConfiguration` translate the (default) values into a `SibnetConfig` / `AniboomConfig` and register the SDK clients as Spring beans, threading the existing `RotatingUserAgentProvider.stableDesktop()` value into the SDK config so all providers share one User-Agent (the value operators see in our outbound-traffic dashboards).
- `service.provider.sibnet.SibnetDecoderService` and `service.provider.aniboom.AniboomDecoderService` are now **thin adapters** (~30 lines each) that delegate to the SDK clients and translate `*DecodeResult` → `ProviderDecodeResult`. Class names are intentionally unchanged — `SourcesController`, `ProvidersController` and the multi-source ranker keep their imports.
- `service.provider.sibnet.SibnetSourceParser`, `service.provider.aniboom.AniboomSourceParser` are **deleted** from orinuno-app. They are now only available via the SDK packages.
- The `SibnetVideoNotFoundException` previously nested inside `SibnetDecoderService` is gone; the SDK now translates 404 directly to `SIBNET_VIDEO_NOT_FOUND` on the result. No call site was catching the exception type — it was only used internally.

### Test placement

- SDK-pure tests (decoder regex, parser shapes, config builder validation, end-to-end decode via stubbed `WebClient.exchangeFunction`) live in `sibnet-sdk/src/test/` (10 tests) and `aniboom-sdk/src/test/` (8 tests). They use no Spring context.
- Two adapter wiring tests (`SibnetDecoderServiceAdapterTest`, `AniboomDecoderServiceAdapterTest`) live in `orinuno-app` and exercise only the SDK→orinuno result translation, including both `SibnetDecoderService.decode(long)` and `decode(String)` overloads.
- Controller and integration tests stay in orinuno-app, just with updated SDK imports — but in this case the controllers depend on the adapter, not the SDK directly, so no controller test had to change.

## Consequences

### Wins

- **Symmetric module layout**: every provider now lives in its own SDK module. The architecture is uniform — Sibnet, Aniboom, JutSu (and any future Sovetromantika / VK / Sibnet alternative) follow the same template.
- **Standalone consumability**: Sibnet and Aniboom each pull in only Spring WebFlux + Reactor (+ Jackson for Aniboom), no MyBatis/MySQL/Liquibase/orinuno types.
- **Faster local-test cycles**: changing the Sibnet regex now rebuilds + retests a 4-class module in ~1.5s instead of triggering the full orinuno-app suite (9 minutes with Testcontainers).
- **No duplicate state**: both SDKs are stateless — no rate limiter, no session manager — so the "shared singleton" wiring pitfall that bit JutSu (ADR 0012's "Risks") simply doesn't apply here.

### Costs

- **Two more Maven modules to build**: net build-time cost is negligible (~1.5s extra per provider, in parallel with everything else). Reactor wiring already accounts for the per-module overhead.
- **More duplication of `ProviderDecodeResult`-shaped records**: now four shape-identical records exist (`ProviderDecodeResult`, `JutsuDecodeResult`, `SibnetDecodeResult`, `AniboomDecodeResult`). We accept this on purpose — the user explicitly rejected a shared SPI module ("Давай делать… M3 standalone *-sdk").

### Risks

- **API-incompatible refactors of `*DecodeResult`**: changing the shape now requires touching N modules. Mitigation: the records are tiny (4 fields) and the contract is set in stone by the orinuno HTTP API; we don't expect drift.
- **`AniboomDecoder.htmlEntityDecode` is hand-rolled**: it covers the five entities Aniboom actually uses (`&quot; &amp; &lt; &gt; &#39; &apos;`). If Aniboom ever emits any other entity in `data-parameters` we'll silently drop it. Mitigation: `ANIBOOM_JSON_PARSE_ERROR` will surface immediately and the test suite includes a fixture for the entity decoder.

## Blocked on

Nothing — Step 3 ships in the same PR cycle as Steps 1 and 2.

## Tracker

| Item | Status |
|------|--------|
| `sibnet-sdk` Maven module | ✅ done |
| `aniboom-sdk` Maven module | ✅ done |
| Public APIs (`SibnetClient`, `AniboomClient`, configs, results, error codes) | ✅ done |
| Decoder + parser moved out of orinuno-app | ✅ done |
| orinuno-app adapters (`SibnetDecoderService`, `AniboomDecoderService`) | ✅ done |
| orinuno-app Spring configs (`SibnetSdkConfiguration`, `AniboomSdkConfiguration`) | ✅ done |
| Tests moved + adapter tests added | ✅ done (10 SDK + 8 SDK + 5 adapter) |
| Documentation: ADR + READMEs + AGENTS.md update | ✅ done |
| Step 4 — `orinuno-app` rewires controllers directly on SDK facades | ⏳ next |
| Step 5 — final docs + release notes | ⏳ planned |
