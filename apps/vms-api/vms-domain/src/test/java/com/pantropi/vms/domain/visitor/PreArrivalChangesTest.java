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
 * US-08.1.3 · T-08.1.3.1 — visitor-level amend and cancel on the aggregate. Pure, no I/O.
 *
 * <p>T-08.1.3.1 asks for both operations to be exercised from <em>every</em> visitor status, which
 * is done with {@code @EnumSource} rather than by sampling — the status nobody tested is the one
 * that turns out to permit a retroactive edit.
 */
class PreArrivalChangesTest {

    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");
    private static final Instant NOW = Instant.parse("2030-05-30T00:00:00Z");

    // ---- AC-1: amend, from every status ----

    @ParameterizedTest
    @EnumSource(value = VisitorStatus.class, names = {"PENDING", "APPROVED"})
    @DisplayName("AC-1: a pending or approved visitor's details can be corrected in place")
    void editableStatusesCanBeAmended(VisitorStatus status) {
        VisitorRequest request = requestWithVisitorIn(status);
        Visitor before = request.visitors().get(0);

        request.amendVisitor(before.id(), "Grace Hopper", "grace@example.test", null,
                "Navy", null);

        Visitor after = request.visitors().get(0);
        assertThat(after.fullName()).isEqualTo("Grace Hopper");
        assertThat(after.emailValue()).isEqualTo("grace@example.test");
        // Identity and status survive the correction: a credential references this id, and a
        // "corrected" visitor with a new id would be a different person downstream.
        assertThat(after.id()).isEqualTo(before.id());
        assertThat(after.status()).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = VisitorStatus.class,
            names = {"CHECKED_IN", "INSIDE", "CHECKED_OUT", "EXPIRED", "CANCELLED", "NO_SHOW"})
    @DisplayName("AC-4: every other status refuses an amendment, naming itself")
    void everyOtherStatusRefusesAmendment(VisitorStatus status) {
        VisitorRequest request = requestWithVisitorIn(status);
        Visitor before = request.visitors().get(0);

        assertThatThrownBy(() -> request.amendVisitor(before.id(), "Sneaky Edit", null, null,
                null, null))
                .isInstanceOfSatisfying(VisitorRequest.VisitorNotEditable.class,
                        e -> assertThat(e.status).isEqualTo(status));

        assertThat(request.visitors().get(0).fullName()).isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("AC-1: amendment validation is a fresh entry's — a malformed email is refused and "
            + "nothing changes")
    void amendmentObeysEntryValidation() {
        VisitorRequest request = requestWithVisitorIn(VisitorStatus.PENDING);
        UUID visitorId = request.visitors().get(0).id();

        assertThatThrownBy(() -> request.amendVisitor(visitorId, "Ada", "not-an-email", null,
                null, null))
                .isInstanceOf(Visitor.InvalidVisitorDetail.class)
                .hasMessageNotContaining("not-an-email");

        assertThat(request.visitors().get(0).emailValue()).isEqualTo("ada@example.test");
    }

    @Test
    @DisplayName("an id naming nobody on the request is its own failure, not a status complaint")
    void unknownVisitorIsItsOwnFailure() {
        VisitorRequest request = requestWithVisitorIn(VisitorStatus.PENDING);

        assertThatThrownBy(() -> request.amendVisitor(UUID.randomUUID(), "Ada", null, null,
                null, null))
                .isInstanceOf(VisitorRequest.VisitorNotFoundInRequest.class);
    }

    // ---- AC-2: cancel, from every status ----

    @ParameterizedTest
    @EnumSource(value = VisitorStatus.class, names = {"PENDING", "APPROVED"})
    @DisplayName("AC-2: a pending or approved visitor can be withdrawn")
    void editableStatusesCanBeCancelled(VisitorStatus status) {
        VisitorRequest request = requestWithVisitorIn(status);
        UUID visitorId = request.visitors().get(0).id();

        request.cancelVisitor(visitorId, NOW);

        assertThat(request.visitors().get(0).status()).isEqualTo(VisitorStatus.CANCELLED);
    }

    @ParameterizedTest
    @EnumSource(value = VisitorStatus.class,
            names = {"CHECKED_IN", "INSIDE", "CHECKED_OUT", "EXPIRED", "CANCELLED", "NO_SHOW"})
    @DisplayName("AC-4: every other status refuses cancellation from the pre-arrival workflow")
    void everyOtherStatusRefusesCancellation(VisitorStatus status) {
        VisitorRequest request = requestWithVisitorIn(status);
        UUID visitorId = request.visitors().get(0).id();

        assertThatThrownBy(() -> request.cancelVisitor(visitorId, NOW))
                .isInstanceOf(VisitorRequest.VisitorNotEditable.class);

        assertThat(request.visitors().get(0).status()).isEqualTo(status);
        assertThat(request.status()).isNotEqualTo(RequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("AC-2: cancelling the last active visitor cancels the request with them")
    void lastActiveVisitorTakesTheRequest() {
        VisitorRequest request = singleVisitorRequest();
        UUID visitorId = request.visitors().get(0).id();

        boolean requestCancelled = request.cancelVisitor(visitorId, NOW);

        assertThat(requestCancelled).isTrue();
        assertThat(request.status()).isEqualTo(RequestStatus.CANCELLED);
        assertThat(request.cancelledAfterApproval()).isFalse();
    }

    @Test
    @DisplayName("AC-2: cancelling one of several leaves the request and the others untouched")
    void oneOfSeveralLeavesTheRest() {
        VisitorRequest request = twoVisitorRequest();
        UUID first = request.visitors().get(0).id();

        boolean requestCancelled = request.cancelVisitor(first, NOW);

        assertThat(requestCancelled).isFalse();
        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(request.visitors().get(1).status()).isEqualTo(VisitorStatus.PENDING);
    }

    @Test
    @DisplayName("AC-2: cancelling the second of two then takes the request")
    void secondOfTwoTakesTheRequest() {
        VisitorRequest request = twoVisitorRequest();
        request.cancelVisitor(request.visitors().get(0).id(), NOW);

        boolean requestCancelled = request.cancelVisitor(request.visitors().get(1).id(), NOW);

        assertThat(requestCancelled).isTrue();
        assertThat(request.status()).isEqualTo(RequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("AC-3's flag: withdrawing the last visitor of an approved request records that an "
            + "approval was withdrawn")
    void lastVisitorOfApprovedRequestFlagsTheApproval() {
        VisitorRequest request = singleVisitorRequest();
        request.approve(UUID.randomUUID(), null, NOW);

        boolean requestCancelled = request.cancelVisitor(request.visitors().get(0).id(), NOW);

        assertThat(requestCancelled).isTrue();
        assertThat(request.cancelledAfterApproval()).isTrue();
    }

    @Test
    @DisplayName("a visitor still checked in keeps the request alive — non-terminal means active")
    void checkedInVisitorKeepsTheRequestAlive() {
        VisitorRequest request = twoVisitorRequest();
        request.visitors().get(1).moveTo(VisitorStatus.CHECKED_IN);

        boolean requestCancelled = request.cancelVisitor(request.visitors().get(0).id(), NOW);

        // Somebody is in the building on this request; withdrawing the other guest must not cancel
        // the visit that is happening.
        assertThat(requestCancelled).isFalse();
        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
    }

    // ---- AC-1: reschedule ----

    @Test
    @DisplayName("AC-1: the window moves while the request is submitted, and still moves once "
            + "approved — wider than amend, on purpose")
    void rescheduleIsLegalSubmittedAndApproved() {
        TimeWindow moved = new TimeWindow(WINDOW_FROM.plusSeconds(3600),
                WINDOW_TO.plusSeconds(3600));

        VisitorRequest submitted = singleVisitorRequest();
        submitted.reschedule(moved);
        assertThat(submitted.window()).isEqualTo(moved);

        VisitorRequest approved = singleVisitorRequest();
        approved.approve(UUID.randomUUID(), null, NOW);
        assertThatCode(() -> approved.reschedule(moved)).doesNotThrowAnyException();
        assertThat(approved.status()).isEqualTo(RequestStatus.APPROVED);
    }

    @ParameterizedTest
    @EnumSource(value = RequestStatus.class, names = {"REJECTED", "CANCELLED"})
    @DisplayName("a terminal request cannot be rescheduled")
    void terminalRequestCannotBeRescheduled(RequestStatus terminal) {
        VisitorRequest request = singleVisitorRequest();
        if (terminal == RequestStatus.REJECTED) {
            request.reject(UUID.randomUUID(), "Host on leave", NOW);
        } else {
            request.cancel(NOW);
        }

        assertThatThrownBy(() -> request.reschedule(
                new TimeWindow(WINDOW_FROM.plusSeconds(60), WINDOW_TO)))
                .isInstanceOf(VisitorRequest.RequestNotPending.class);
    }

    @ParameterizedTest
    @EnumSource(value = VisitorStatus.class, names = {"CHECKED_IN", "INSIDE"})
    @DisplayName("AC-4: a visit under way cannot be rescheduled — it happened when it happened")
    void visitUnderWayCannotBeRescheduled(VisitorStatus inProgress) {
        VisitorRequest request = twoVisitorRequest();
        request.visitors().get(1).moveTo(inProgress);
        TimeWindow before = request.window();

        assertThatThrownBy(() -> request.reschedule(
                new TimeWindow(WINDOW_FROM.plusSeconds(3600), WINDOW_TO.plusSeconds(3600))))
                .isInstanceOf(VisitorRequest.VisitorNotEditable.class);

        assertThat(request.window()).isEqualTo(before);
    }

    // ---- helpers ----

    private static VisitorRequest singleVisitorRequest() {
        return VisitorRequest.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Site inspection",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", null, null, null)));
    }

    private static VisitorRequest twoVisitorRequest() {
        return VisitorRequest.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Site inspection",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", null, null, null),
                        Visitor.named("Alan Turing", "alan@example.test", null, null, null)));
    }

    /** A request whose sole visitor sits in the given status, moved there directly. */
    private static VisitorRequest requestWithVisitorIn(VisitorStatus status) {
        VisitorRequest request = singleVisitorRequest();
        if (status == VisitorStatus.APPROVED) {
            request.approve(UUID.randomUUID(), null, NOW);
        } else if (status != VisitorStatus.PENDING) {
            request.visitors().get(0).moveTo(status);
        }
        return request;
    }
}
