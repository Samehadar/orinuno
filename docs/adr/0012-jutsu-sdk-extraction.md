# ADR 0012 — JutSu SDK extraction (`jutsu-sdk` standalone module)

- **Status**: Accepted
- **Date**: 2026-05-03
- **Deciders**: orinuno maintainers
- **Related**: ADR 0001 (Kodik SDK extraction pilot), ADR 0009 (PLAYER-4 — JutSu decoder), Step 2 of the API/module split roadmap (`*-sdk` per-provider modules)

## Context

Step 1 of the API/module split (per-source API tier under `/api/v1/sources/{provider}` and `/api/v1/anime/{contentId}/episodes/{s}/{e}/sources`) shipped without touching module boundaries. Step 2 takes the next step: extract the JutSu provider into a standalone Maven module so external consumers can depend on a JutSu client without taking on Spring Boot, MyBatis, MySQL, Liquibase, or any of the orinuno-app domain types.

JutSu was picked as the pilot for the per-source SDKs (over Sibnet / Aniboom / Kodik) because:

- It has the **largest surface area** that needs to move (decoder, rate limiter, session manager, source URL parser — ~860 source lines + ~490 test lines), so the extraction exercise covers all the awkward bits at once.
- It has the **strongest external value**: full DLE auth + sticky cookies + Yandex CDN session-binding aren't trivial to re-implement, and the only competing reference (`AnimeParsers/jutsu_parser_async.py`) is Python-only.
- It has the **clearest external dependency**: only Spring WebFlux + Reactor + Bucket4j + Micrometer; no MyBatis, no MySQL, no domain types from orinuno-app.

The user explicitly chose **M3 (standalone `*-sdk` modules)** with **shared code duplicated** across modules (no shared SPI module).

## Decision

Create a new Maven module `jutsu-sdk/` under the existing reactor build:

```
orinuno-parent (reactor)
├── kodik-sdk-drift     (PR3 pilot — schema-drift detector)
├── jutsu-sdk           ← THIS ADR
└── orinuno-app         (Spring Boot service)
```

### Module shape

| File | What lives here |
|------|----------------|
| `com.orinuno.jutsu.JutsuClient` | Public facade — `decode(url) → Mono<JutsuDecodeResult>`, plus accessors for `rateLimiter()` and `sessionManager()` so adjacent components (CDN proxy) can share state. |
| `com.orinuno.jutsu.JutsuConfig` | Immutable record + builder. Defaults match orinuno-app's production-tested values. |
| `com.orinuno.jutsu.JutsuDecodeResult` | Result type. **Duplicate** of orinuno-app's `ProviderDecodeResult` — see "Duplication tax" below. |
| `com.orinuno.jutsu.JutsuErrorCodes` | Stable string constants for the error-code vocabulary the runbook depends on. |
| `com.orinuno.jutsu.auth.JutsuSessionManager` | DLE login + sticky cookie jar. |
| `com.orinuno.jutsu.decoder.JutsuDecoder` | Episode-page fetcher + extractor. Renamed from `JutsuDecoderService` (no Spring `@Service` here). |
| `com.orinuno.jutsu.parser.JutsuSourceParser` | Pure URL-shape parser. |
| `com.orinuno.jutsu.ratelimit.JutsuRateLimiter` | Bucket4j-backed 1 RPS hard cap. Decoupled from `OrinunoProperties` via a `DoubleSupplier` constructor parameter so consumers can plug their own config source. |

### orinuno-app changes

- `JutsuSdkConfiguration` translates `OrinunoProperties.JutsuProperties` into a `JutsuConfig` and registers the SDK's `JutsuClient`, `JutsuRateLimiter` and `JutsuSessionManager` as Spring beans.
- `service.provider.jutsu.JutsuDecoderService` is now a **thin adapter** (~40 lines) that delegates to `JutsuClient.decode(...)` and converts `JutsuDecodeResult` → `ProviderDecodeResult`. The class name is intentionally unchanged so existing controllers (`SourcesController`, `ProvidersController`, `KodikDecodeOrchestrator`) don't need touching.
- `service.provider.jutsu.JutsuRateLimiter`, `JutsuSessionManager`, `JutsuSourceParser` are **deleted** from orinuno-app.
- `JutsuStreamProxyController` updates its imports to the SDK packages but keeps its constructor signature.

### Test placement

- SDK-pure tests (decoder regex, rate limiter behaviour, session manager wiring, config builder) live in `jutsu-sdk/src/test/`. They use `WebClient.builder().exchangeFunction(...)` stubbing — no Spring context, no application properties.
- One adapter wiring test (`JutsuDecoderServiceAdapterTest`) lives in `orinuno-app` and exercises only the SDK→orinuno result translation.
- Controller and integration tests stay in orinuno-app, just with updated SDK imports.

## Consequences

### Wins

- **Standalone consumability**: a third-party project can `<dependency>jutsu-sdk</dependency>` without taking on MyBatis, MySQL, Liquibase, Spring Boot, or any orinuno-specific type.
- **Smaller blast radius**: changes to JutSu's HTML parsing / session protocol now only rebuild `jutsu-sdk` (and re-run its 36 tests in ~2s) before the larger orinuno-app suite needs to retest the wiring.
- **Forced separation**: removing `OrinunoProperties` from the SDK surface caught two coupling bugs we'd been ignoring (`@Component` annotations and direct `@Autowired` constructors that needed `@Autowired` markers because Spring couldn't decide between overloads — both are non-issues outside Spring).
- **Roadmap unblock**: lays the template for `sibnet-sdk`, `aniboom-sdk` and `kodik-sdk` (Steps 3-4 of the agreed roadmap).

### Costs

- **Duplication tax**: `JutsuDecodeResult` is shape-identical to orinuno-app's `ProviderDecodeResult`, and `RotatingUserAgentProvider`'s `stableDesktop()` value gets re-derived from the SDK's `JutsuConfig.userAgent()`. We accept this on purpose — the alternative (a third "shared SPI" module) is what the user explicitly rejected ("Давай делать… M3 standalone *-sdk").
- **Two more Maven modules to build**: ~3s extra per full build. Not measurable next to the existing 9-minute integration-test phase.
- **Spring WebFlux is now a transitive of `jutsu-sdk`**: every consumer pulls in `spring-webflux` + `spring-context` + `reactor-netty-http`. Acceptable because (a) the SDK is reactive by design, (b) the alternative (`java.net.http.HttpClient` + `CompletableFuture`) is heavier to test and harder to wire into Spring consumers, (c) all real-world consumers we know of are already on Spring.

### Risks

- **Version drift between modules**: the parent POM pins `spring-webflux.version`, `reactor.version`, `reactor-netty.version`, `bucket4j.version`, `micrometer.version` to whatever Spring Boot 3.5.x ships. We need to bump these together when we bump Boot.
- **Rate-limit double counting**: if `JutsuSdkConfiguration` accidentally builds two `JutsuRateLimiter` beans, outbound RPS would double silently. We protect against this by exposing a `JutsuClient(config, rateLimiter, sessionManager, webClientBuilder)` constructor that **must reuse** the existing limiter; a smoke test in `verify_step2` covers it.

## Blocked on

Nothing — Step 2 ships in the same PR cycle as Step 1.

## Tracker

| Item | Status |
|------|--------|
| `jutsu-sdk` Maven module | ✅ done |
| `JutsuClient` + `JutsuConfig` + `JutsuDecodeResult` + `JutsuErrorCodes` | ✅ done |
| `JutsuRateLimiter` / `JutsuSessionManager` / `JutsuDecoder` / `JutsuSourceParser` moved | ✅ done |
| `orinuno-app` adapter (`JutsuDecoderService`) | ✅ done |
| `orinuno-app` Spring config (`JutsuSdkConfiguration`) | ✅ done |
| Tests moved + adapter test added | ✅ done (36 SDK tests + 2 adapter tests) |
| Documentation: ADR + jutsu-sdk README + cross-link from ADR 0001 | ✅ done |
| Step 3 — `sibnet-sdk` + `aniboom-sdk` | ⏳ next |
| Step 4 — `kodik-sdk` (merge with `kodik-sdk-drift`) | ⏳ planned |
