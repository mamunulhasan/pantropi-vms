package com.pantropi.vms.domain.visitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-07.4.1 · T-07.4.1.1 — the approval rules on the aggregate. Pure, no I/O.
 *
 * <p>Covers every source state, the optional note, the elapsed-window refusal, and the cascade
 * including the mixed set the story singles out.
 */
class ApprovalRulesTest {

    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");
    private static final Instant BEFORE_VISIT = Instant.parse("2030-05-30T00:00:00Z");

    // ---- AC-1: what an approval does ----

    @Test
    @DisplayName("AC-1: the request becomes approved, records the approver and the deciding time, "
            + "and every visitor follows")
    void approvalMovesEverything() {
        VisitorRequest request = submitted();
        UUID approver = UUID.randomUUID();

        request.approve(approver, null, BEFORE_VISIT);

        assertThat(request.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(request.approvedBy()).isEqualTo(approver);
        assertThat(request.decidedAt()).isEqualTo(BEFORE_VISIT);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.APPROVED));
    }

    // ---- AC-4: the optional note ----

    @Test
    @DisplayName("AC-4: a note is trimmed and stored with the decision")
    void noteIsStored() {
        VisitorRequest request = submitted();
        request.approve(UUID.randomUUID(), "  Cleared with building security  ", BEFORE_VISIT);

        assertThat(request.decisionReason()).isEqualTo("Cleared with building security");
    }

    @Test
    @DisplayName("AC-4: the note is optional — absent and blank are the same thing")
    void noteIsOptional() {
        for (String blank : new String[]{null, "", "   ", "\t\n"}) {
            VisitorRequest request = submitted();
            request.approve(UUID.randomUUID(), blank, BEFORE_VISIT);

            assertThat(request.status()).isEqualTo(RequestStatus.APPROVED);
            // Blank is stored as absent rather than as an empty string, so a reader asking "was a
            // reason given?" gets one answer instead of two that mean the same thing.
            assertThat(request.decisionReason()).isNull();
        }
    }

    @Test
    @DisplayName("AC-4: an over-long note is refused and the request stays undecided")
    void noteIsBounded() {
        VisitorRequest request = submitted();

        assertThatThrownBy(() ->
                request.approve(UUID.randomUUID(), "x".repeat(1001), BEFORE_VISIT))
                .isInstanceOf(VisitorRequest.DecisionReasonTooLong.class);

        // Checked before the transition: a rejected note must not leave a half-applied approval.
        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(request.approvedBy()).isNull();
    }

    // ---- AC-5: every source state ----

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"APPROVED", "REJECTED", "CANCELLED"})
    @DisplayName("AC-5: approving an already-decided request is refused and changes nothing")
    void everyDecidedSourceStateIsRefused(RequestStatus decided) {
        VisitorRequest request = inState(decided);
        UUID before = request.approvedBy();

        assertThatThrownBy(() -> request.approve(UUID.randomUUID(), "second thoughts", BEFORE_VISIT))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(request.status()).isEqualTo(decided);
        assertThat(request.approvedBy()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-5: only a submitted request can be approved")
    void submittedIsTheOnlyApprovableState() {
        assertThatCode(() -> submitted().approve(UUID.randomUUID(), null, BEFORE_VISIT))
                .doesNotThrowAnyException();
    }

    // ---- AC-7: the elapsed window ----

    @Test
    @DisplayName("AC-7: a window that has fully elapsed cannot be approved")
    void elapsedWindowIsRefused() {
        VisitorRequest request = submitted();
        Instant afterTheVisit = WINDOW_TO.plusSeconds(1);

        assertThatThrownBy(() ->
                request.approve(UUID.randomUUID(), null, afterTheVisit))
                .isInstanceOf(VisitorRequest.WindowAlreadyElapsed.class);

        // Nothing written: approving is refused, not partially applied.
        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(request.decidedAt()).isNull();
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.PENDING));
    }

    @Test
    @DisplayName("AC-7: the boundary — at the closing instant the window is over, one moment "
            + "earlier it is not")
    void elapsedWindowBoundary() {
        // The window is half-open [from, to): a visit ending at 11:00 gives nobody until 11:00:00,
        // so approving *at* 11:00 would issue a credential with no remaining validity. Asserted
        // because "already elapsed" is exactly the kind of comparison that ends up off by one.
        assertThatThrownBy(() -> submitted().approve(UUID.randomUUID(), null, WINDOW_TO))
                .isInstanceOf(VisitorRequest.WindowAlreadyElapsed.class);

        assertThatCode(() -> submitted()
                .approve(UUID.randomUUID(), null, WINDOW_TO.minusMillis(1)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-7: a visit already under way can still be approved")
    void inProgressWindowIsApprovable() {
        // Mid-window is not elapsed. A late approval of a visit happening now is legitimate — the
        // guest is at reception — and refusing it would be the rule overreaching.
        assertThatCode(() -> submitted()
                .approve(UUID.randomUUID(), null, WINDOW_FROM.plusSeconds(60)))
                .doesNotThrowAnyException();
    }

    // ---- AC-1 with a mixed visitor set ----

    @Test
    @DisplayName("AC-1: a visitor cancelled individually is not approved along with the rest")
    void mixedVisitorSetIsRespected() {
        VisitorRequest request = submitted();
        request.visitors().get(1).moveTo(VisitorStatus.CANCELLED);

        request.approve(UUID.randomUUID(), null, BEFORE_VISIT);

        assertThat(request.visitors().get(0).status()).isEqualTo(VisitorStatus.APPROVED);
        assertThat(request.visitors().get(1).status()).isEqualTo(VisitorStatus.CANCELLED);
    }

    @Test
    @DisplayName("the ids exposed for the event are the visitors' own, and nothing else")
    void visitorIdsCarryNoPii() {
        // AC-2 puts these on the wire, so the accessor must expose identifiers only. If it ever
        // returned the Visitor objects, a consumer serialising them would publish names and emails.
        VisitorRequest request = submitted();

        assertThat(request.visitorIds())
                .containsExactly(request.visitors().get(0).id(), request.visitors().get(1).id());
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
            case APPROVED -> request.approve(UUID.randomUUID(), null, BEFORE_VISIT);
            case REJECTED -> request.reject(UUID.randomUUID(), "Host on leave", BEFORE_VISIT);
            case CANCELLED -> request.cancel(BEFORE_VISIT);
            case SUBMITTED -> { /* already there */ }
        }
        return request;
    }
}
