package com.pantropi.vms.domain.visitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-07.4.2 · T-07.4.2.1 — the rejection rules on the aggregate. Pure, no I/O.
 *
 * <p>T-07.4.2.1 asks for rejection to be exercised from every source state, and for the mandatory
 * reason to hold against blank, whitespace-only and null alike.
 */
class RejectionRulesTest {

    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");
    private static final Instant NOW = Instant.parse("2030-05-30T00:00:00Z");

    // ---- AC-1 ----

    @Test
    @DisplayName("AC-1: the request is rejected, the decider and reason are recorded, and every "
            + "visitor is cancelled")
    void rejectionMovesEverything() {
        VisitorRequest request = submitted();
        UUID decider = UUID.randomUUID();

        request.reject(decider, "Host is on leave that week", NOW);

        assertThat(request.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(request.approvedBy()).isEqualTo(decider);
        assertThat(request.decisionReason()).isEqualTo("Host is on leave that week");
        assertThat(request.decidedAt()).isEqualTo(NOW);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.CANCELLED));
    }

    // ---- AC-2: the reason is mandatory ----

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n", " \t\n "})
    @DisplayName("AC-2: a blank or whitespace-only reason is refused and nothing changes")
    void blankReasonRefused(String blank) {
        VisitorRequest request = submitted();

        assertThatThrownBy(() -> request.reject(UUID.randomUUID(), blank, NOW))
                .isInstanceOf(VisitorRequest.RejectionReasonRequired.class);

        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(request.approvedBy()).isNull();
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.PENDING));
    }

    @Test
    @DisplayName("AC-2: an absent reason is refused too — null is not a shortcut past the rule")
    void nullReasonRefused() {
        VisitorRequest request = submitted();

        assertThatThrownBy(() -> request.reject(UUID.randomUUID(), null, NOW))
                .isInstanceOf(VisitorRequest.RejectionReasonRequired.class);

        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
    }

    @Test
    @DisplayName("the reason is trimmed before it is stored")
    void reasonIsTrimmed() {
        VisitorRequest request = submitted();
        request.reject(UUID.randomUUID(), "   Building closed for maintenance   ", NOW);

        assertThat(request.decisionReason()).isEqualTo("Building closed for maintenance");
    }

    @Test
    @DisplayName("the reason is bounded, and an over-long one changes nothing")
    void reasonIsBounded() {
        VisitorRequest request = submitted();

        assertThatCode(() -> submitted().reject(UUID.randomUUID(), "x".repeat(1000), NOW))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> request.reject(UUID.randomUUID(), "x".repeat(1001), NOW))
                .isInstanceOf(VisitorRequest.DecisionReasonTooLong.class);

        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
    }

    // ---- AC-5 and every other source state ----

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"APPROVED", "REJECTED", "CANCELLED"})
    @DisplayName("AC-5: rejecting an already-decided request is refused and changes nothing")
    void everyDecidedSourceStateIsRefused(RequestStatus decided) {
        VisitorRequest request = inState(decided);
        String reasonBefore = request.decisionReason();

        assertThatThrownBy(() ->
                request.reject(UUID.randomUUID(), "Changed my mind", NOW))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(request.status()).isEqualTo(decided);
        assertThat(request.decisionReason()).isEqualTo(reasonBefore);
    }

    @Test
    @DisplayName("AC-5: an approval is withdrawn by cancelling, never by rejecting")
    void approvalIsNotReversibleByRejection() {
        // The two say different things about the world, so they are not interchangeable: a rejection
        // means never granted, a cancellation of an approval means granted and then withdrawn.
        VisitorRequest request = submitted();
        request.approve(UUID.randomUUID(), null, NOW);

        assertThatThrownBy(() -> request.reject(UUID.randomUUID(), "On reflection, no", NOW))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);
        assertThatCode(() -> request.cancel(NOW)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a request whose window has passed can still be rejected")
    void elapsedWindowIsStillRejectable() {
        // Deliberately unlike approval (US-07.4.1 AC-7): approving a finished visit would mint an
        // already-expired credential, but refusing one costs nothing and may be exactly what the
        // record should say.
        VisitorRequest request = submitted();

        assertThatCode(() -> request.reject(UUID.randomUUID(), "Never turned up to arrange it",
                WINDOW_TO.plusSeconds(86_400))).doesNotThrowAnyException();

        assertThat(request.status()).isEqualTo(RequestStatus.REJECTED);
    }

    // ---- AC-6: the reason is stored as written ----

    @Test
    @DisplayName("AC-6: markup in a reason is stored verbatim, not stripped or altered")
    void markupIsStoredAsText() {
        // Sanitising here would corrupt a legitimate reason — "declined, 5 < 10 people" — while
        // doing nothing about the real risk, which lives at render time in the tenant's browser.
        VisitorRequest request = submitted();
        String hostile = "<script>alert('x')</script> & \"quoted\" \\ backslash";

        request.reject(UUID.randomUUID(), hostile, NOW);

        assertThat(request.decisionReason()).isEqualTo(hostile);
    }

    // ---- helpers ----

    private static VisitorRequest submitted() {
        return VisitorRequest.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Quarterly review",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", null, null, null),
                        Visitor.named("Alan Turing", "alan@example.test", null, null, null)));
    }

    private static VisitorRequest inState(RequestStatus status) {
        VisitorRequest request = submitted();
        switch (status) {
            case APPROVED -> request.approve(UUID.randomUUID(), null, NOW);
            case REJECTED -> request.reject(UUID.randomUUID(), "Host on leave", NOW);
            case CANCELLED -> request.cancel(NOW);
            case SUBMITTED -> { /* already there */ }
        }
        return request;
    }
}
