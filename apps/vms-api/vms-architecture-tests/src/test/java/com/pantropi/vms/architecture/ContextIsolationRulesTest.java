package com.pantropi.vms.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * US-01.2.2 · T-01.2.2.2 — bounded contexts do not read each other's persistence types
 * (AC-1, workflow doc §8: cross-context communication is by domain event or explicit port,
 * never a shared table or a borrowed repository).
 *
 * <p>One rule per context: that context's {@code *Entity}/{@code *Repository}/
 * {@code *JpaRepository} types in infrastructure may be referenced only from within the
 * same context (any layer) or bootstrap wiring. The contexts are empty today — the rules
 * pass vacuously and arm themselves as code arrives.
 */
@AnalyzeClasses(packages = "com.pantropi.vms", importOptions = ImportOption.DoNotIncludeTests.class)
class ContextIsolationRulesTest {

    private static ArchRule isolation(String ctx) {
        return noClasses()
                .that().resideOutsideOfPackages("..vms." + "domain." + ctx + "..",
                        "..vms.application." + ctx + "..",
                        "..vms.infrastructure." + ctx + "..",
                        "..vms.interfaces." + ctx + "..",
                        "com.pantropi.vms.bootstrap..")
                .should().dependOnClassesThat()
                .haveNameMatching(".*\\.infrastructure\\." + ctx + "\\..*(Entity|Repository)")
                .because("context '" + ctx + "' owns its persistence types; other contexts "
                        + "integrate via domain events or ports (workflow doc §8, AC-1)");
    }

    @ArchTest static final ArchRule masterdata_isolated   = isolation("masterdata");
    @ArchTest static final ArchRule visitor_isolated      = isolation("visitor");
    @ArchTest static final ArchRule credential_isolated   = isolation("credential");
    @ArchTest static final ArchRule entry_isolated        = isolation("entry");
    @ArchTest static final ArchRule notification_isolated = isolation("notification");
    @ArchTest static final ArchRule reporting_isolated    = isolation("reporting");
    @ArchTest static final ArchRule identity_isolated     = isolation("identity");
}
