package com.pantropi.vms.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * US-07.5.1 · T-07.5.1.3 — nothing outside the aggregate sets a status (AC-4).
 *
 * <p>The state machine is only an integrity control if it is the <em>only</em> way through. A
 * service that assigned {@code status = APPROVED} directly would satisfy every test about the
 * transition table while defeating the thing the table exists for.
 */
@AnalyzeClasses(packages = "com.pantropi.vms", importOptions = ImportOption.DoNotIncludeTests.class)
class RequestLifecycleRulesTest {

    /**
     * The status mutators stay package-private, so only the visitor aggregate can call them.
     *
     * <p>Compile-enforced already — this rule is what stops someone widening the modifier later and
     * quietly opening the door. That is a one-word change in a diff nobody would flag.
     */
    @ArchTest
    static final ArchRule visitor_status_mutators_stay_inside_the_aggregate =
            classes()
                    .that().haveFullyQualifiedName("com.pantropi.vms.domain.visitor.Visitor")
                    .should(haveNoPublicStatusMutator())
                    .because("a visitor's status is a consequence of a decision about the request; "
                            + "made settable from outside, the cascade rules become advisory "
                            + "(US-07.5.1 AC-3/AC-4)");

    /**
     * Only the aggregate may consult the transition table.
     *
     * <p>A use case that asked {@code isLegal} and then wrote the status itself would be
     * reimplementing the guard beside it, and the two would drift. Asking is the aggregate's job;
     * everyone else calls {@code approve}, {@code reject} or {@code cancel} and handles the refusal.
     */
    @ArchTest
    static final ArchRule only_the_aggregate_consults_the_transition_table =
            noClasses()
                    .that().resideOutsideOfPackages("..domain.visitor..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName(
                            "com.pantropi.vms.domain.visitor.RequestTransitions")
                    .because("the transition guard is the aggregate's, not a rule others re-apply "
                            + "for themselves (US-07.5.1 T-07.5.1.1)");

    private static ArchCondition<JavaClass> haveNoPublicStatusMutator() {
        return new ArchCondition<>("keep its status mutators package-private") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    boolean mutatesStatus = method.getName().equals("moveTo")
                            || method.getName().startsWith("setStatus")
                            || method.getName().startsWith("markApproved")
                            || method.getName().startsWith("markCancelled");
                    if (mutatesStatus && method.getModifiers().contains(JavaModifier.PUBLIC)) {
                        events.add(SimpleConditionEvent.violated(item,
                                item.getFullName() + "." + method.getName()
                                        + " is public; a visitor's status must only be changed by "
                                        + "the request aggregate's cascade"));
                    }
                }
            }
        };
    }
}
