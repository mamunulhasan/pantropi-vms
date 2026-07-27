package com.pantropi.vms.domain.visitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the visitor domain (US-07.1.1, T-07.1.1.1). Pure — no Spring, no database.
 */
class VisitorRequestTest {

    private final Instant from = Instant.parse("2026-08-01T09:00:00Z");
    private final Instant to = from.plus(2, ChronoUnit.HOURS);
    private final UUID tenant = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    private Visitor guest() {
        return Visitor.named("Ada Lovelace", "ada@example.test", "+880100000000", "Analytical Ltd");
    }

    private VisitorRequest submitted() {
        return VisitorRequest.submit(tenant, host, requester, new TimeWindow(from, to),
                "Quarterly review", List.of(guest()));
    }

    // ---- TimeWindow ----

    @Test
    @DisplayName("AC-4: a window must end strictly after it starts")
    void windowMustBePositive() {
        assertThatThrownBy(() -> new TimeWindow(from, from))
                .isInstanceOf(TimeWindow.InvalidTimeWindow.class);
        assertThatThrownBy(() -> new TimeWindow(to, from))
                .isInstanceOf(TimeWindow.InvalidTimeWindow.class);
        assertThat(new TimeWindow(from, to).contains(from.plusSeconds(60))).isTrue();
        assertThat(new TimeWindow(from, to).contains(to)).isFalse();   // half-open
    }

    // ---- submission ----

    @Test
    @DisplayName("AC-1: a submitted request is pre-scheduled, submitted, with pending visitors")
    void submissionShape() {
        VisitorRequest r = submitted();
        assertThat(r.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(r.visitKind()).isEqualTo(VisitKind.PRE_SCHEDULED);
        assertThat(r.tenantId()).isEqualTo(tenant);
        assertThat(r.requestedBy()).isEqualTo(requester);
        assertThat(r.approvedBy()).isNull();
        assertThat(r.visitors()).singleElement()
                .satisfies(v -> assertThat(v.status()).isEqualTo(VisitorStatus.PENDING));
    }

    @Test
    @DisplayName("a request must name at least one visitor, and cannot exceed the cap")
    void visitorCountBounds() {
        assertThatThrownBy(() -> VisitorRequest.submit(tenant, host, requester,
                new TimeWindow(from, to), null, List.of()))
                .isInstanceOf(VisitorRequest.NoVisitorsNamed.class);

        List<Visitor> tooMany = java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> Visitor.named("Guest " + i, null, null, null)).toList();
        assertThatThrownBy(() -> VisitorRequest.submit(tenant, host, requester,
                new TimeWindow(from, to), null, tooMany))
                .isInstanceOf(VisitorRequest.TooManyVisitors.class);
    }

    @Test
    @DisplayName("the tenant and submitter are mandatory — the aggregate cannot exist without them")
    void tenantAndSubmitterRequired() {
        assertThatThrownBy(() -> VisitorRequest.submit(null, host, requester,
                new TimeWindow(from, to), null, List.of(guest())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> VisitorRequest.submit(tenant, host, null,
                new TimeWindow(from, to), null, List.of(guest())))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("the visitor list is not modifiable from outside the aggregate")
    void visitorsAreEncapsulated() {
        VisitorRequest r = submitted();
        assertThatThrownBy(() -> r.visitors().add(guest()))
                .isInstanceOf(UnsupportedOperationException.class);
        r.addVisitor(Visitor.named("Grace Hopper", null, null, null));
        assertThat(r.visitors()).hasSize(2);
    }

    // ---- visitor detail validation ----

    @Test
    @DisplayName("a visitor needs a name; optional fields are length-bounded and trimmed")
    void visitorDetailRules() {
        assertThatThrownBy(() -> Visitor.named("  ", null, null, null))
                .isInstanceOfSatisfying(Visitor.InvalidVisitorDetail.class,
                        e -> assertThat(e.field()).isEqualTo("visitor name"));
        assertThatThrownBy(() -> Visitor.named("Ada", "x".repeat(321), null, null))
                .isInstanceOf(Visitor.InvalidVisitorDetail.class);
        assertThat(Visitor.named("  Ada  ", null, null, null).fullName()).isEqualTo("Ada");
        assertThat(Visitor.named("Ada", "  ", null, null).email()).isNull();
    }

    @Test
    @DisplayName("a validation message names the field but never echoes the offending value")
    void validationDoesNotEchoPersonalData() {
        String email = "very.private.person@example.test".repeat(20);
        assertThatThrownBy(() -> Visitor.named("Ada", email, null, null))
                .isInstanceOf(Visitor.InvalidVisitorDetail.class)
                .hasMessageNotContaining("very.private.person");
    }

    // ---- transitions ----

    @Test
    @DisplayName("approval moves the request and every visitor forward, and records the approver")
    void approval() {
        VisitorRequest r = submitted();
        UUID approver = UUID.randomUUID();
        r.approve(approver);
        assertThat(r.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(r.approvedBy()).isEqualTo(approver);
        assertThat(r.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.APPROVED));
    }

    @Test
    @DisplayName("rejection and cancellation cancel the visitors")
    void rejectionAndCancellation() {
        VisitorRequest rejected = submitted();
        rejected.reject(UUID.randomUUID());
        assertThat(rejected.status()).isEqualTo(RequestStatus.REJECTED);
        assertThat(rejected.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.CANCELLED));

        VisitorRequest cancelled = submitted();
        cancelled.cancel();
        assertThat(cancelled.status()).isEqualTo(RequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("a decided request refuses further changes")
    void decidedRequestIsClosed() {
        VisitorRequest r = submitted();
        r.approve(UUID.randomUUID());
        assertThatThrownBy(() -> r.addVisitor(guest()))
                .isInstanceOf(VisitorRequest.RequestNotPending.class);
        assertThatThrownBy(() -> r.approve(UUID.randomUUID()))
                .isInstanceOf(VisitorRequest.RequestNotPending.class);
        assertThatThrownBy(r::cancel).isInstanceOf(VisitorRequest.RequestNotPending.class);
    }

    // ---- enum mapping ----

    @Test
    @DisplayName("enums round-trip through their database wire form without drift")
    void enumMapping() {
        for (RequestStatus s : RequestStatus.values()) {
            assertThat(RequestStatus.fromDb(s.dbValue())).isEqualTo(s);
        }
        for (VisitKind k : VisitKind.values()) {
            assertThat(VisitKind.fromDb(k.dbValue())).isEqualTo(k);
        }
        for (VisitorStatus s : VisitorStatus.values()) {
            assertThat(VisitorStatus.fromDb(s.dbValue())).isEqualTo(s);
        }
        assertThat(VisitKind.PRE_SCHEDULED.dbValue()).isEqualTo("pre_scheduled");
    }
}
