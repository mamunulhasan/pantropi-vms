package com.pantropi.vms.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * US-03.4.1 · T-03.4.1.3 — a query over scope-sensitive data must get its predicate from the
 * scoping component (AC-5).
 *
 * <p>The policy is one class and easy to review. The bypass is the thing that could be anywhere: a
 * new repository method that reads {@code vms.visitor_requests} and simply does not ask. This rule
 * is worth more than the policy itself, because it is what stops the sixth query from quietly
 * undoing what the first five were careful about.
 *
 * <p>Deliberately structural rather than behavioural. A test that checked isolation on the queries
 * that exist would pass forever while saying nothing about the one added next week.
 */
@AnalyzeClasses(packages = "com.pantropi.vms", importOptions = ImportOption.DoNotIncludeTests.class)
class ScopingRulesTest {

    /** The domain types whose rows belong to one tenant or one reception. */
    private static final List<String> SCOPE_SENSITIVE = List.of(
            "com.pantropi.vms.domain.visitor.VisitorRequest",
            "com.pantropi.vms.domain.visitor.Visitor",
            // A tenant's staff directory (US-10.1.1). Listed here so the rule below forces the
            // repository through ScopePolicy: AC-4 puts the cross-tenant refusal in the
            // repository precisely so no call site can forget it.
            "com.pantropi.vms.domain.visitor.Host");

    private static final String SCOPE_POLICY =
            "com.pantropi.vms.application.identity.usecase.ScopePolicy";

    /**
     * Any infrastructure class that touches a scope-sensitive type must also depend on the policy.
     *
     * <p>Blunt on purpose. It does not verify that the predicate is applied <em>correctly</em> —
     * that is what the integration tests are for — but it does make bypassing it impossible to do
     * silently, which is the failure that would otherwise never be noticed.
     */
    @ArchTest
    static final ArchRule scoped_reads_go_through_the_policy =
            classes()
                    .that().resideInAPackage("..infrastructure..")
                    .and(new DescribedTouches(SCOPE_SENSITIVE))
                    .should(dependOnScopePolicy())
                    .because("a query over scope-sensitive data must obtain its predicate from "
                            + "ScopePolicy (US-03.4.1 AC-2/AC-5); writing an isolation condition "
                            + "inline is how one query ends up disagreeing with the rest");

    /**
     * Nothing outside the policy may construct a permissive filter.
     *
     * <p>{@code Unrestricted} is the answer for a building-wide role and nothing else. Allowing any
     * adapter to mint one would let a single query opt itself out of isolation with a line that
     * reads as innocuous.
     */
    @ArchTest
    static final ArchRule only_the_policy_grants_unrestricted_access =
            noClasses()
                    .that().resideOutsideOfPackages(
                            "..application.identity.usecase..", "..domain.identity..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("com.pantropi.vms.domain.identity.ScopeFilter$Unrestricted")
                    .because("only ScopePolicy decides that a principal sees everything "
                            + "(US-03.4.1); an adapter minting Unrestricted for itself is an "
                            + "isolation bypass that reads like ordinary code");

    private static ArchCondition<JavaClass> dependOnScopePolicy() {
        return new ArchCondition<>("obtain its scope predicate from ScopePolicy") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                boolean uses = item.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> d.getTargetClass().getFullName().equals(SCOPE_POLICY));
                if (!uses) {
                    events.add(SimpleConditionEvent.violated(item,
                            item.getFullName() + " reads scope-sensitive data without consulting "
                                    + SCOPE_POLICY));
                }
            }
        };
    }

    /** True for a class that references any scope-sensitive domain type. */
    private static final class DescribedTouches
            extends com.tngtech.archunit.base.DescribedPredicate<JavaClass> {

        private final List<String> types;

        DescribedTouches(List<String> types) {
            super("reference a scope-sensitive domain type");
            this.types = types;
        }

        @Override
        public boolean test(JavaClass item) {
            return item.getDirectDependenciesFromSelf().stream()
                    .anyMatch(d -> types.contains(d.getTargetClass().getFullName()));
        }
    }
}
