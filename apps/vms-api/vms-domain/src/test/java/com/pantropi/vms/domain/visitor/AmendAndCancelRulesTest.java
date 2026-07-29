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
 * US-07.1.3 · T-07.1.3.1 — amendment and cancellation on the aggregate. Pure, no I/O.
 */
class AmendAndCancelRulesTest {

    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");
    private static final Instant NOW = Instant.parse("2030-05-30T00:00:00Z");

    // ---- AC-2, AC-3: cancellation ----

    @Test
    @DisplayName("AC-2: cancelling a submitted request cancels every visitor with it")
    void cancelFromSubmitted() {
        VisitorRequest request = submitted();

        request.cancel(NOW);

        assertThat(request.status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(request.decidedAt()).isEqualTo(NOW);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.CANCELLED));
        // Nothing was granted, so there is nothing to revoke.
        assertThat(request.cancelledAfterApproval()).isFalse();
    }

    @Test
    @DisplayName("AC-3: cancelling an approved request is allowed and raises the revocation signal")
    void cancelFromApproved() {
        VisitorRequest request = submitted();
        request.approve(UUID.randomUUID(), null, NOW);

        request.cancel(NOW);

        assertThat(request.status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.CANCELLED));
        // The distinction the use case needs: a credential may already exist for this visit.
        assertThat(request.cancelledAfterApproval()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"REJECTED", "CANCELLED"})
    @DisplayName("AC-4: cancelling a terminal request is refused and changes nothing")
    void cancelFromTerminalStates(RequestStatus terminal) {
        VisitorRequest request = inState(terminal);

        assertThatThrownBy(() -> request.cancel(NOW))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(request.status()).isEqualTo(terminal);
        assertThat(request.cancelledAfterApproval()).isFalse();
    }

    @Test
    @DisplayName("a refused cancellation cannot leave the revocation flag set")
    void refusedCancellationLeavesNoSignal() {
        // The flag is written after the guard, so an attempt that throws cannot make a later reader
        // believe a credential needs revoking.
        VisitorRequest request = submitted();
        request.approve(UUID.randomUUID(), null, NOW);
        request.cancel(NOW);
        assertThat(request.cancelledAfterApproval()).isTrue();

        assertThatThrownBy(() -> request.cancel(NOW))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(request.status()).isEqualTo(RequestStatus.CANCELLED);
    }

    // ---- AC-1: amendment ----

    @Test
    @DisplayName("AC-1: the window, purpose, host and visitors can all be amended")
    void amendEverything() {
        VisitorRequest request = submitted();
        UUID newHost = UUID.randomUUID();
        TimeWindow moved = new TimeWindow(WINDOW_FROM.plusSeconds(3600),
                WINDOW_TO.plusSeconds(3600));

        request.amend(newHost, moved, "Rescheduled review",
                List.of(Visitor.named("Grace Hopper", null, null, null, null)));

        assertThat(request.hostId()).isEqualTo(newHost);
        assertThat(request.window()).isEqualTo(moved);
        assertThat(request.purpose()).isEqualTo("Rescheduled review");
        assertThat(request.visitors()).singleElement()
                .satisfies(v -> assertThat(v.fullName()).isEqualTo("Grace Hopper"));
        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
    }

    @Test
    @DisplayName("AC-1: a null field is left alone — this is a partial edit, not a replacement")
    void amendIsPartial() {
        VisitorRequest request = submitted();
        UUID originalHost = request.hostId();

        request.amend(null, null, "Only the purpose changed", null);

        assertThat(request.purpose()).isEqualTo("Only the purpose changed");
        assertThat(request.hostId()).isEqualTo(originalHost);
        assertThat(request.window().from()).isEqualTo(WINDOW_FROM);
        assertThat(request.visitors()).hasSize(2);
    }

    @Test
    @DisplayName("AC-1: an amendment with nothing in it is legal and changes nothing")
    void emptyAmendmentIsHarmless() {
        VisitorRequest request = submitted();

        assertThatCode(() -> request.amend(null, null, null, null)).doesNotThrowAnyException();

        assertThat(request.purpose()).isEqualTo("Quarterly review");
        assertThat(request.visitors()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"APPROVED", "REJECTED", "CANCELLED"})
    @DisplayName("AC-4: a decided request cannot be amended, and nothing is touched")
    void amendingADecidedRequestIsRefused(RequestStatus decided) {
        // Altering what someone decided on would make their decision a record of something that no
        // longer exists.
        VisitorRequest request = inState(decided);
        String purposeBefore = request.purpose();

        assertThatThrownBy(() -> request.amend(UUID.randomUUID(), null, "Sneaky edit", null))
                .isInstanceOf(VisitorRequest.RequestNotPending.class);

        assertThat(request.purpose()).isEqualTo(purposeBefore);
        assertThat(request.status()).isEqualTo(decided);
    }

    @Test
    @DisplayName("an amendment is validated before anything is applied")
    void invalidAmendmentAppliesNothing() {
        VisitorRequest request = submitted();
        UUID newHost = UUID.randomUUID();

        // The host is legal and would be applied first if the checks ran as it went; the empty
        // visitor list is not.
        assertThatThrownBy(() -> request.amend(newHost, null, "New purpose", List.of()))
                .isInstanceOf(VisitorRequest.NoVisitorsNamed.class);

        assertThat(request.hostId()).isNotEqualTo(newHost);
        assertThat(request.purpose()).isEqualTo("Quarterly review");
    }

    @Test
    @DisplayName("an amended request obeys the same visitor and purpose bounds as a new one")
    void amendmentObeysTheSameBounds() {
        assertThatThrownBy(() -> submitted().amend(null, null, "x".repeat(501), null))
                .isInstanceOf(IllegalArgumentException.class);

        List<Visitor> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> Visitor.named("Guest " + i, null, null, null, null)).toList();
        assertThatThrownBy(() -> submitted().amend(null, null, null, tooMany))
                .isInstanceOf(VisitorRequest.TooManyVisitors.class);
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
