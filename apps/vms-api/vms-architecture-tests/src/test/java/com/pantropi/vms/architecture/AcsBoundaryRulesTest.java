package com.pantropi.vms.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * US-01.2.2 · T-01.2.2.2 — THE LOAD-BEARING RULE (AC-2).
 *
 * <p>No ACS concept may exist or be referenced outside its two sanctioned homes:
 * {@code application.acs} (the port, in VMS domain terms) and {@code infrastructure.acs}
 * (the anti-corruption adapter, the only place wire-format types may live).
 *
 * <p>This is the mechanical expression of CON-01 (SRS B1), CON-02 (SRS B1) and
 * NFR-MNT-01 (SRS B1) — and the standing mitigation for TODO-02: because these rules hold,
 * the unpublished UAL contract is an adapter concern, not an architecture risk.
 */
@AnalyzeClasses(packages = "com.pantropi.vms", importOptions = ImportOption.DoNotIncludeTests.class)
class AcsBoundaryRulesTest {

    private static final String ACS_PORT_PKG    = "com.pantropi.vms.application.acs..";
    private static final String ACS_ADAPTER_PKG = "com.pantropi.vms.infrastructure.acs..";

    @ArchTest
    static final ArchRule adapter_internals_stay_inside_the_acs_module =
            noClasses().that().resideOutsideOfPackages(ACS_ADAPTER_PKG)
                    .should().dependOnClassesThat().resideInAPackage(ACS_ADAPTER_PKG)
                    .because("wire-format ACS types never leak: the rest of VMS reaches ACS only "
                            + "through the port in " + ACS_PORT_PKG + " (CON-02 (SRS B1), ADR-0002)");

    @ArchTest
    static final ArchRule vendor_vocabulary_only_in_sanctioned_packages =
            noClasses().that().resideOutsideOfPackages(ACS_PORT_PKG, ACS_ADAPTER_PKG)
                    .should().haveSimpleNameContaining("Acs")
                    .orShould().haveSimpleNameContaining("Ual")
                    .orShould().haveSimpleNameContaining("FlapBarrier")
                    .because("a type named after the ACS vendor or its hardware outside the "
                            + "integration boundary means ACS concepts are bleeding into VMS "
                            + "(CON-01 (SRS B1), AC-2)");

    @ArchTest
    static final ArchRule the_port_speaks_domain_language_not_wire_language =
            noClasses().that().resideInAPackage(ACS_PORT_PKG)
                    .should().dependOnClassesThat().resideInAPackage(ACS_ADAPTER_PKG)
                    .because("the port is defined in VMS terms; if it references adapter types, "
                            + "the anti-corruption layer has inverted (ADR-0002)")
                    .allowEmptyShould(true); // port package holds only package-info until F-11.1
}
