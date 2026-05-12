/*
 * Bounded-context boundary guards.
 *
 * Codifies ADR 0016 §"Boundary discipline" rules 1-2 and ADR 0017 §"Boundary discipline" rule 7
 * as ArchUnit assertions. ADR 0018 (per-source service split) carries these forward as
 * pre-extraction prerequisites — any new cross-context coupling must fail this test before
 * Kodik / jut.su services move out of orinuno-app.
 *
 * Rules:
 *   1. catalog.internal sealed — only catalog.* may reference com.orinuno.catalog.internal..
 *   2. catalog is source-agnostic — must not import com.orinuno.jutsu.., com.orinuno.client..,
 *      com.kodik.token.. (Kodik internals). Cross-source data enters via SourceCatalogEvent
 *      (orinuno-source-contract) or CatalogIdentityRequest, never via L1 entities.
 *   3. jutsu↛kodik isolation — com.orinuno.jutsu.. must not import com.orinuno.client..
 *      or com.kodik.token.. (Kodik internals). Same principle, opposite direction.
 *   4. orinuno-app↛kodik-sdk-internals isolation (ADR 0021 §E1). After Blocks B/C/D moved every
 *      Kodik write-path slice into orinuno-source-kodik, orinuno-app must not reach back into
 *      Kodik SDK internals. Only three sub-packages of com.kodik.. are gateway-level utilities
 *      and stay allow-listed: com.kodik.drift (shared drift detector wired by all SDKs),
 *      com.kodik.client.http (RotatingUserAgentProvider — shared User-Agent factory),
 *      and com.kodik.client.embed (KodikIdType enum — gateway DTO shape). Anything else
 *      (com.kodik.client root + dto/exception subpackages, com.kodik.token, com.kodik.decoder)
 *      is per-source-private and must not be referenced from orinuno-app.
 *
 * SDK types (com.kodik.., com.jutsu.., com.sibnet.., com.aniboom..) and source-contract
 * types (com.orinuno.contract.source..) are library-level — allowed everywhere unless a
 * specific rule above carves them out.
 */
package com.orinuno.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.orinuno",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class BoundedContextArchitectureTest {

    /**
     * Allow-list predicate for ADR 0021 §E1. Matches any class living under {@code com.kodik..}
     * that is NOT in one of the three gateway-level utility sub-packages. Used by the
     * orinuno-app↛kodik-sdk-internals rule below. Allow-list (instead of deny-list) keeps the
     * guard tight against future SDK package additions: any new {@code com.kodik.foo} subpackage
     * is blocked by default and a contributor has to justify adding it to this list.
     */
    private static final DescribedPredicate<JavaClass> KODIK_SDK_INTERNALS =
            JavaClass.Predicates.resideInAPackage("com.kodik..")
                    .and(
                            JavaClass.Predicates.resideOutsideOfPackages(
                                    "com.kodik.drift..",
                                    "com.kodik.client.http..",
                                    "com.kodik.client.embed.."))
                    .as("Kodik SDK internals (com.kodik.. outside the drift / client.http /"
                            + " client.embed gateway-level allow-list)");

    @ArchTest
    static final ArchRule catalog_internal_is_sealed =
            noClasses()
                    .that()
                    .resideOutsideOfPackage("com.orinuno.catalog..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.orinuno.catalog.internal..")
                    .because(
                            "catalog.internal is package-local per ADR 0016 §\"New bounded context:"
                                    + " catalog\". Cross-context access must go through"
                                    + " com.orinuno.catalog.api.CatalogPublicApi.");

    @ArchTest
    static final ArchRule catalog_does_not_depend_on_source_internals =
            noClasses()
                    .that()
                    .resideInAPackage("com.orinuno.catalog..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.orinuno.jutsu..", "com.orinuno.client..", "com.kodik.token..")
                    .because(
                            "Catalog must stay source-agnostic per ADR 0017. Per-source data enters"
                                + " via com.orinuno.contract.source.SourceCatalogEvent (handled by"
                                + " CatalogSinkEventEmitter) or CatalogIdentityRequest — never via"
                                + " L1 entities or per-source HTTP clients.");

    @ArchTest
    static final ArchRule jutsu_does_not_depend_on_kodik_internals =
            noClasses()
                    .that()
                    .resideInAPackage("com.orinuno.jutsu..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("com.orinuno.client..", "com.kodik.token..")
                    .because(
                            "jut.su context must not reach into Kodik internals — symmetric to ADR"
                                + " 0018 per-source service isolation. After the split each source"
                                + " becomes a standalone deployable; cross-source talk goes through"
                                + " /api/v1/source-events/ready, not Java imports.");

    @ArchTest
    static final ArchRule orinuno_app_does_not_reach_into_kodik_sdk_internals =
            noClasses()
                    .that()
                    .resideInAPackage("com.orinuno..")
                    .should()
                    .dependOnClassesThat(KODIK_SDK_INTERNALS)
                    .because(
                            "ADR 0021 §E1 — Kodik write-path code lives in"
                                + " orinuno-source-kodik. orinuno-app talks to Kodik via"
                                + " HTTP (KodikUpstreamProxyFilter reverse-proxies"
                                + " /api/v1/parse/, /api/v1/stream/, /api/v1/hls/,"
                                + " /api/v1/download/, /api/v1/export/) and via the"
                                + " meter-readonly DS for L2/L3 reads. Direct Kodik"
                                + " SDK calls (KodikApiClient, token registry, decoder"
                                + " orchestrator, DTO/exception types) re-introduce the"
                                + " coupling Phase 2/5 dismantled and let the monolith"
                                + " seam silently regrow.");
}
