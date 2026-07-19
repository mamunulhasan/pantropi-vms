package com.pantropi.vms.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * US-01.2.2 · T-01.2.2.1 — layer dependencies point inward only, and the inner layers are
 * framework-free. A violation fails the build naming the offending class (AC-1, AC-4).
 *
 * <p>Requirement: NFR-MNT-01 (SRS B1); workflow doc §7.
 */
@AnalyzeClasses(packages = "com.pantropi.vms", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependencyRulesTest {

    @ArchTest
    static final ArchRule layers_point_inward_only =
            layeredArchitecture().consideringOnlyDependenciesInLayers()
                    // package-info-only packages emit no .class files, so early layers are
                    // empty until real code lands; the rule arms as each layer populates
                    .withOptionalLayers(true)
                    .layer("domain").definedBy("com.pantropi.vms.domain..")
                    .layer("application").definedBy("com.pantropi.vms.application..")
                    .layer("infrastructure").definedBy("com.pantropi.vms.infrastructure..")
                    .layer("interfaces").definedBy("com.pantropi.vms.interfaces..")
                    .layer("bootstrap").definedBy("com.pantropi.vms.bootstrap..")
                    .whereLayer("domain").mayOnlyBeAccessedByLayers(
                            "application", "infrastructure", "interfaces", "bootstrap")
                    .whereLayer("application").mayOnlyBeAccessedByLayers(
                            "infrastructure", "interfaces", "bootstrap")
                    .whereLayer("infrastructure").mayOnlyBeAccessedByLayers("bootstrap")
                    .whereLayer("interfaces").mayOnlyBeAccessedByLayers("bootstrap")
                    .because("Clean Architecture: dependencies point inward only "
                            + "(NFR-MNT-01 (SRS B1); workflow doc §7)");

    private static final String[] FRAMEWORK_PACKAGES = {
            "org.springframework..", "jakarta..", "javax.persistence..",
            "org.hibernate..", "com.fasterxml.jackson..", "org.apache.kafka..",
            "redis.clients..", "io.lettuce.."
    };

    @ArchTest
    static final ArchRule domain_is_framework_free =
            noClasses().that().resideInAPackage("com.pantropi.vms.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                    .because("the domain layer is pure Java — a framework import here is a "
                            + "design error, not a style issue (US-01.2.1 AC-1/AC-5)")
                    .allowEmptyShould(true); // domain has no compiled classes yet

    @ArchTest
    static final ArchRule application_is_framework_free =
            noClasses().that().resideInAPackage("com.pantropi.vms.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(FRAMEWORK_PACKAGES)
                    .because("application classes are wired by outer-layer configuration, never "
                            + "annotated or framework-coupled (US-01.2.1 T-01.2.1.3)")
                    .allowEmptyShould(true);
}
