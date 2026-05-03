# ADR 0014 — Controllers wired directly on SDK facades (`*DecoderService` adapters removed)

- **Status**: Accepted
- **Date**: 2026-05-03
- **Deciders**: orinuno maintainers
- **Related**: ADR 0001 (Kodik SDK extraction pilot), ADR 0012 (JutSu SDK extraction), ADR 0013 (Sibnet & Aniboom SDK extraction), Step 4 of the API/module split roadmap

## Context

Steps 2 (ADR 0012) and 3 (ADR 0013) extracted the JutSu, Sibnet and Aniboom decoders into standalone SDK modules under `com.orinuno.{jutsu,sibnet,aniboom}`. To keep blast radius small at extraction time, every controller in `orinuno-app` kept calling thin `*DecoderService` adapters that delegated to the SDK facade and translated the SDK result record into orinuno-app's HTTP-facing `ProviderDecodeResult`.

After Step 3 the adapters did exactly two things:

1. Inject a single SDK-client field as a Spring bean.
2. Run `sdk.decode(url).map(SDK→ProviderDecodeResult mapping)`.

The mapping is identical across all three providers — `success` / `qualities` / `format` are copied verbatim, `errorCode` propagates as-is. The adapter therefore added a class file, a Spring `@Service` annotation, three constructor parameters and ~30 lines of code per provider, with no behaviour beyond `Mono.map`. As a single `@Service` it also forced controllers to mock one extra Mockito field per provider in their unit tests (`SibnetDecoderService` instead of `SibnetClient`), which obscured what the controller is actually calling.

## Decision

Drop the three `*DecoderService` adapter classes entirely. Inject the SDK facades (`JutsuClient`, `SibnetClient`, `AniboomClient`) directly into the controllers and translate the SDK result via a single static helper:

```java
case "SIBNET" -> sibnetClient.decode(url)
        .map(ProviderDecodeResults::from)
        .map(ResponseEntity::ok);
```

### `ProviderDecodeResults` static mapper

Lives at `com.orinuno.service.provider.ProviderDecodeResults`. Three overloads — `from(JutsuDecodeResult)`, `from(SibnetDecodeResult)`, `from(AniboomDecodeResult)` — each three lines:

```java
public static ProviderDecodeResult from(SibnetDecodeResult sdk) {
    if (sdk.success()) {
        return ProviderDecodeResult.success(sdk.qualities(), sdk.format());
    }
    return ProviderDecodeResult.failure(sdk.errorCode());
}
```

Centralising the mapping in one place means:

- The contract stays in one file. If we ever introduce a new `ProviderDecodeResult` field, the change radius is exactly one class.
- Controllers stay thin (the dispatch switch is now the only orinuno-app logic).
- The mapping is unit-testable as a pure function (`ProviderDecodeResultsTest` — 6 tests, no Spring, no Mockito).

### `ProviderDecodeResult` stays in `orinuno-app`

The orinuno-facing HTTP contract record stays under `com.orinuno.service.provider` because it is part of the `orinuno-app` API surface, not the SDK surface. The four shape-identical `*DecodeResult` records (one per SDK + one in orinuno-app) are intentionally kept separate per the M3 standalone-SDK decision (see ADR 0012, ADR 0013).

### Controller changes

| Controller | Before | After |
|------------|--------|-------|
| `SourcesController` | 4 fields: `KodikVideoDecoderService`, `SibnetDecoderService`, `AniboomDecoderService`, `JutsuDecoderService` | 4 fields: `KodikVideoDecoderService`, `SibnetClient`, `AniboomClient`, `JutsuClient` |
| `ProvidersController` (deprecated) | 3 fields: `SibnetDecoderService`, `AniboomDecoderService`, `JutsuDecoderService` | 3 fields: `SibnetClient`, `AniboomClient`, `JutsuClient` |
| `JutsuStreamProxyController` | unchanged — already uses `JutsuRateLimiter` + `JutsuSessionManager` from the SDK | unchanged |
| `MultiSourceController` | unchanged — never called the adapters | unchanged |

Public HTTP contract is unchanged: same paths, same request/response shapes, same status codes. Live smoke against `/api/v1/sources/{provider}/decode` returns identical bodies before and after.

## Consequences

### Wins

- **One fewer indirection layer**: controllers now read like "decode via SDK → map → respond", which is what the code actually does.
- **Smaller surface to maintain**: 3 classes + 3 test classes deleted from orinuno-app (~250 lines net).
- **More honest unit tests**: controller tests now mock the SDK facades they actually depend on, so a refactor inside the SDK is caught at test compile time instead of slipping past mocked-adapter mocks.
- **Mapping testable in isolation**: `ProviderDecodeResultsTest` covers the SDK→orinuno translation as a pure function — no controller wiring required.

### Costs

- **Mockito gymnastics in controller tests**: now mock `SibnetClient.decode(String)` instead of `SibnetDecoderService.decode(String)`. Identical signature; only the mocked type changes. Net: zero LoC change in the test scaffolding.
- **One more place that knows about every SDK**: `ProviderDecodeResults` imports all three SDK result types. We accept this — the file's job is exactly to translate between layers, so the cross-layer imports are intentional.

### Risks

- **Breaking change for any downstream consumer that imported `*DecoderService`**: zero such consumers exist outside orinuno (the adapters were never published or referenced from the docs site / READMEs).
- **Dispatch logic now lives only in the controllers**: if a third controller wants per-source decode, it has to repeat the switch. Mitigation: the only consumers are `SourcesController` (canonical) and `ProvidersController` (deprecated alias). If a third appears, factor a `ProviderDispatcher` service then; YAGNI today.

## Blocked on

Nothing — Step 4 ships in the same PR cycle as Step 3.

## Tracker

| Item | Status |
|------|--------|
| `ProviderDecodeResults` static mapper + 6 unit tests | ✅ done |
| `SourcesController` rewired on SDK facades | ✅ done |
| `ProvidersController` rewired on SDK facades | ✅ done |
| `*DecoderService` adapter classes deleted (3) | ✅ done |
| Adapter tests deleted (3) | ✅ done |
| Controller tests now mock SDK facades | ✅ done |
| Empty `service/provider/{jutsu,sibnet,aniboom}` packages cleaned up | ✅ done |
| Full orinuno-app regression | ✅ 633 passed, 1 skipped, 0 failed |
| ADR 0014 + cross-links | ✅ done |
| Step 5 — final docs + release notes | ⏳ next |
