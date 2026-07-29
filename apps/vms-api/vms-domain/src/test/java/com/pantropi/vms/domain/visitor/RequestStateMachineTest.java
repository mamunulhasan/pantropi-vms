package com.pantropi.vms.domain.visitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the request state machine and visitor cascade (US-07.5.1) — pure, no I/O.
 *
 * <p>T-07.5.1.1 asks for every source×target cell to be asserted, legal and illegal. That is done
 * exhaustively below rather than by sampling: the cell that is never tested is the one that turns
 * out to permit something.
 */
class RequestStateMachineTest {

    /** Fixed and well before the 2030 test window, so the elapsed-window rule never fires here. */
    private static final Instant NOW = Instant.parse("2029-12-31T00:00:00Z");

    /** AC-1, written out independently of the table so the test is a second opinion, not an echo. */
    private static final Set<String> EXPECTED_LEGAL = Set.of(
            "SUBMITTED->APPROVED",
            "SUBMITTED->REJECTED",
            "SUBMITTED->CANCELLED",
            "APPROVED->CANCELLED");

    // ---- the transition table, cell by cell ----

    @Test
    @DisplayName("AC-1: every source×target cell matches the specified machine, legal and illegal")
    void everyCell() {
        for (RequestStatus from : RequestStatus.values()) {
            for (RequestStatus to : RequestStatus.values()) {
                boolean expected = EXPECTED_LEGAL.contains(from + "->" + to);
                assertThat(RequestTransitions.isLegal(from, to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("AC-1: an approval cannot become a rejection — that reversal is a cancellation")
    void approvedCannotBeRejected() {
        // The two say different things about the world: a rejection is "never granted", a
        // cancellation of an approval is "granted, then withdrawn".
        assertThat(RequestTransitions.isLegal(RequestStatus.APPROVED, RequestStatus.REJECTED))
                .isFalse();
        assertThat(RequestTransitions.isLegal(RequestStatus.APPROVED, RequestStatus.CANCELLED))
                .isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"REJECTED", "CANCELLED"})
    @DisplayName("AC-4: a terminal state goes nowhere")
    void terminalStatesAreTerminal(RequestStatus terminal) {
        assertThat(RequestTransitions.legalTargets(terminal)).isEmpty();
        for (RequestStatus to : RequestStatus.values()) {
            assertThatThrownBy(() -> RequestTransitions.require(terminal, to))
                    .isInstanceOf(RequestTransitions.IllegalTransition.class);
        }
    }

    @Test
    @DisplayName("the refusal names both ends, so a failure says what was attempted")
    void refusalIsDiagnostic() {
        assertThatThrownBy(() ->
                RequestTransitions.require(RequestStatus.REJECTED, RequestStatus.APPROVED))
                .isInstanceOf(RequestTransitions.IllegalTransition.class)
                .hasMessageContaining("REJECTED").hasMessageContaining("APPROVED");
    }

    @Test
    @DisplayName("a null on either side is illegal rather than a null pointer")
    void nullsAreIllegalNotFatal() {
        assertThat(RequestTransitions.isLegal(null, RequestStatus.APPROVED)).isFalse();
        assertThat(RequestTransitions.isLegal(RequestStatus.SUBMITTED, null)).isFalse();
    }

    // ---- AC-6: exhaustiveness ----

    @Test
    @DisplayName("AC-6: every request status appears in the transition table")
    void everyRequestStatusIsMapped() {
        // Adding a value to the enum without extending the table would give it no rules at all —
        // isLegal would answer false for everything, and the new state would be silently unusable
        // rather than loudly wrong.
        assertThat(RequestTransitions.mappedStates())
                .containsExactlyInAnyOrder(RequestStatus.values());
    }

    @Test
    @DisplayName("AC-6: every visitor status is classified as terminal or not")
    void everyVisitorStatusIsClassified() {
        // The cascade asks "is this terminal?" of every visitor. A status nobody classified would
        // default to movable, which for a checked-out or expired visitor is wrong.
        Set<VisitorStatus> classified = EnumSet.copyOf(VisitorCascade.terminalStatuses());
        Set<VisitorStatus> nonTerminal = EnumSet.allOf(VisitorStatus.class);
        nonTerminal.removeAll(classified);

        assertThat(classified).isNotEmpty();
        // Every value is on exactly one side of the line, which is what this asserts by construction
        // — the point is that the two sets together cover the enum.
        assertThat(classified.size() + nonTerminal.size()).isEqualTo(VisitorStatus.values().length);
        for (VisitorStatus s : VisitorStatus.values()) {
            assertThat(VisitorCascade.isTerminal(s)).isEqualTo(classified.contains(s));
        }
    }

    @Test
    @DisplayName("AC-6: every non-initial request status causes a defined cascade")
    void everyDecisionCascades() {
        for (RequestStatus status : RequestStatus.values()) {
            if (status == RequestStatus.SUBMITTED) {
                continue;   // never transitioned to; a request is created in it
            }
            assertThat(VisitorCascade.cascadingStates())
                    .as("cascade for %s", status)
                    .contains(status);
        }
    }

    // ---- AC-3: the cascade ----

    @Test
    @DisplayName("AC-3: approval approves the visitors; rejection and cancellation cancel them")
    void cascadeDirections() {
        assertThat(VisitorCascade.targetFor(RequestStatus.APPROVED, VisitorStatus.PENDING))
                .contains(VisitorStatus.APPROVED);
        assertThat(VisitorCascade.targetFor(RequestStatus.REJECTED, VisitorStatus.PENDING))
                .contains(VisitorStatus.CANCELLED);
        assertThat(VisitorCascade.targetFor(RequestStatus.CANCELLED, VisitorStatus.PENDING))
                .contains(VisitorStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = VisitorStatus.class,
            names = {"CANCELLED", "CHECKED_OUT", "EXPIRED", "NO_SHOW"})
    @DisplayName("AC-3: a visitor in a terminal state is never moved by a request decision")
    void terminalVisitorsAreNotResurrected(VisitorStatus terminal) {
        for (RequestStatus decision : RequestStatus.values()) {
            assertThat(VisitorCascade.targetFor(decision, terminal))
                    .as("%s should not move a %s visitor", decision, terminal)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("AC-3: approving a request does not resurrect a visitor cancelled individually")
    void mixedVisitorSet() {
        // The case the AC names: someone was deliberately removed from the visit before the
        // decision, and approving the request as a whole is not a decision about them.
        VisitorRequest request = requestWithTwoVisitors();
        Visitor withdrawn = request.visitors().get(1);
        withdrawn.moveTo(VisitorStatus.CANCELLED);

        request.approve(UUID.randomUUID(), null, NOW);

        assertThat(request.visitors().get(0).status()).isEqualTo(VisitorStatus.APPROVED);
        assertThat(request.visitors().get(1).status()).isEqualTo(VisitorStatus.CANCELLED);
    }

    // ---- the aggregate goes through the guard ----

    @Test
    @DisplayName("AC-4: an illegal transition leaves the request and its visitors untouched")
    void illegalTransitionMutatesNothing() {
        VisitorRequest request = requestWithTwoVisitors();
        request.reject(UUID.randomUUID(), "Not this week", NOW);

        assertThatThrownBy(() -> request.approve(UUID.randomUUID(), null, NOW))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(request.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.CANCELLED));
    }

    @Test
    @DisplayName("an approved request can still be cancelled, and its visitors follow")
    void approvedCanBeWithdrawn() {
        VisitorRequest request = requestWithTwoVisitors();
        request.approve(UUID.randomUUID(), null, NOW);

        assertThatCode(() -> request.cancel(NOW)).doesNotThrowAnyException();

        assertThat(request.status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.CANCELLED));
    }

    // ---- US-07.4.2: the mandatory reason ----

    @Test
    @DisplayName("US-07.4.2 AC-2: a blank reason is refused, and nothing changes")
    void rejectionNeedsAReason() {
        for (String blank : new String[]{null, "", "   ", "\t\n"}) {
            VisitorRequest request = requestWithTwoVisitors();
            assertThatThrownBy(() -> request.reject(UUID.randomUUID(), blank, NOW))
                    .isInstanceOf(VisitorRequest.RejectionReasonRequired.class);
            assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
        }
    }

    @Test
    @DisplayName("US-07.4.2: the reason is trimmed, kept, and bounded")
    void reasonIsStored() {
        VisitorRequest request = requestWithTwoVisitors();
        request.reject(UUID.randomUUID(), "  Host is on leave  ", NOW);

        assertThat(request.decisionReason()).isEqualTo("Host is on leave");

        VisitorRequest other = requestWithTwoVisitors();
        assertThatThrownBy(() -> other.reject(UUID.randomUUID(), "x".repeat(1001), NOW))
                .isInstanceOf(VisitorRequest.DecisionReasonTooLong.class);
        assertThat(other.status()).isEqualTo(RequestStatus.SUBMITTED);
    }

    @Test
    @DisplayName("an approval carries no reason — only a refusal has to be justified")
    void approvalHasNoReason() {
        VisitorRequest request = requestWithTwoVisitors();
        request.approve(UUID.randomUUID(), null, NOW);

        assertThat(request.decisionReason()).isNull();
    }

    // ---- helper ----

    private static VisitorRequest requestWithTwoVisitors() {
        return VisitorRequest.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new TimeWindow(Instant.parse("2030-01-01T09:00:00Z"),
                        Instant.parse("2030-01-01T11:00:00Z")),
                "Quarterly review",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", null, null),
                        Visitor.named("Alan Turing", "alan@example.test", null, null)));
    }
}
