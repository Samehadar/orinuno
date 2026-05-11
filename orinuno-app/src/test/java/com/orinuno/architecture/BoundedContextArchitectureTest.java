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
 *      com.orinuno.token.. (Kodik internals). Cross-source data enters via SourceCatalogEvent
 *      (orinuno-source-contract) or CatalogIdentityRequest, never via L1 entities.
 *   3. jutsu↛kodik isolation — com.orinuno.jutsu.. must not import com.orinuno.client..
 *      or com.orinuno.token.. (Kodik internals). Same principle, opposite direction.
 *
 * SDK types (com.kodik.., com.jutsu.., com.sibnet.., com.aniboom..) and source-contract
 * types (com.orinuno.contract.source..) are library-level — allowed everywhere.
 */
package com.orinuno.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.orinuno",
        importOptions = {ImportOption.DoNotIncludeTests.class, ImportOption.DoNotIncludeJars.class})
class BoundedContextArchitectureTest {

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
                            "com.orinuno.jutsu..", "com.orinuno.client..", "com.orinuno.token..")
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
                    .resideInAnyPackage("com.orinuno.client..", "com.orinuno.token..")
                    .because(
                            "jut.su context must not reach into Kodik internals — symmetric to ADR"
                                + " 0018 per-source service isolation. After the split each source"
                                + " becomes a standalone deployable; cross-source talk goes through"
                                + " /api/v1/source-events/ready, not Java imports.");
}
