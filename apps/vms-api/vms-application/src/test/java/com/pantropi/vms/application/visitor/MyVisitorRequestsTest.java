package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.port.VisitorRequestQueries;
import com.pantropi.vms.application.visitor.usecase.MyVisitorRequests;
import com.pantropi.vms.application.visitor.usecase.RequestDecision;
import com.pantropi.vms.domain.visitor.RequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-07.6.1 · T-07.6.1.2 — turning query parameters into something the query cannot be harmed by.
 */
class MyVisitorRequestsTest {

    private static final UUID ACTOR = UUID.randomUUID();

    private final RecordingQueries queries = new RecordingQueries();
    private final RecordingAudit audit = new RecordingAudit();
    private final MyVisitorRequests useCase = new MyVisitorRequests(queries, audit);

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    @DisplayName("every real status is accepted, by database value and by enum name")
    void everyStatusParses(RequestStatus status) {
        useCase.list(status.dbValue(), null, null, 0, 20);
        assertThat(queries.lastFilter.status()).isEqualTo(status);

        useCase.list(status.name(), null, null, 0, 20);
        assertThat(queries.lastFilter.status()).isEqualTo(status);

        useCase.list(status.dbValue().toUpperCase(), null, null, 0, 20);
        assertThat(queries.lastFilter.status()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aproved", "APPROVED_", "pending", "' OR 1=1 --", "submitted rejected"})
    @DisplayName("AC-2: an unrecognised status is refused rather than silently dropped")
    void unknownStatusIsRefused(String bogus) {
        // Dropping it would answer ?status=aproved with the tenant's entire list — which reads as
        // "nothing has been decided" when in fact everything has, and the caller cannot tell.
        assertThatThrownBy(() -> useCase.list(bogus, null, null, 0, 20))
                .isInstanceOf(MyVisitorRequests.UnknownStatus.class)
                .hasMessageContaining("submitted");     // the message names the valid values

        assertThat(queries.lastFilter).as("nothing was queried").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("an absent or blank status means no status filter, not an error")
    void blankStatusMeansNoFilter(String blank) {
        useCase.list(blank, null, null, 0, 20);
        assertThat(queries.lastFilter.status()).isNull();

        useCase.list(null, null, null, 0, 20);
        assertThat(queries.lastFilter.status()).isNull();
    }

    @Test
    @DisplayName("the page bound is applied before the query sees it")
    void pageIsBounded() {
        useCase.list(null, null, null, 0, 10_000);

        assertThat(queries.lastFilter.page().size()).isEqualTo(PageRequest.MAX_SIZE);
    }

    @Test
    @DisplayName("the date range is passed through untouched")
    void datesPassThrough() {
        Instant from = Instant.parse("2030-06-01T00:00:00Z");
        Instant to = Instant.parse("2030-07-01T00:00:00Z");

        useCase.list(null, from, to, 0, 20);

        assertThat(queries.lastFilter.visitFrom()).isEqualTo(from);
        assertThat(queries.lastFilter.visitTo()).isEqualTo(to);
    }

    @Test
    @DisplayName("AC-4: a request that is absent — or another tenant's — is the same not-found")
    void detailNotFound() {
        // The store returns empty for both cases, and this use case cannot tell them apart either.
        // That is the property: there is no branch here that could answer them differently.
        assertThatThrownBy(() -> useCase.detail(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(RequestDecision.NotFound.class);
    }

    @Test
    @DisplayName("a visible request comes back whole")
    void detailFound() {
        UUID id = UUID.randomUUID();
        queries.detail = new VisitorRequestQueries.Detail(id, "rejected", "Host A", "Review",
                Instant.parse("2030-06-01T09:00:00Z"), Instant.parse("2030-06-01T11:00:00Z"),
                Instant.parse("2030-05-01T09:00:00Z"), "Frances Manager",
                Instant.parse("2030-05-02T09:00:00Z"), "Host is on leave",
                List.of(new VisitorRequestQueries.VisitorLine("Ada Lovelace", "Analytical Ltd", "Guest",
                        "cancelled")));

        var detail = useCase.detail(ACTOR, id);

        assertThat(detail.decisionReason()).isEqualTo("Host is on leave");
        assertThat(detail.decidedBy()).isEqualTo("Frances Manager");
        assertThat(detail.visitors()).singleElement()
                .satisfies(v -> assertThat(v.fullName()).isEqualTo("Ada Lovelace"));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("US-07.3.3 AC-2: reading the detail is itself audited, with "
            + "no personal data in the entry")
    void detailReadIsAudited() {
        UUID id = UUID.randomUUID();
        queries.detail = new VisitorRequestQueries.Detail(id, "approved", "Host A", "Review",
                Instant.parse("2030-06-01T09:00:00Z"), Instant.parse("2030-06-01T11:00:00Z"),
                Instant.parse("2030-05-01T09:00:00Z"), "Frances Manager", null, null,
                List.of(new VisitorRequestQueries.VisitorLine("Ada Lovelace", "Analytical Ltd",
                        "Guest", "approved")));

        useCase.detail(ACTOR, id);

        assertThat(audit.entries).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo("visitor_request.view");
            assertThat(e.actor()).isEqualTo(ACTOR);
            assertThat(e.entityId()).isEqualTo(id.toString());
            // T-07.3.3.1: null, so the row's after_state is null. The entry says personal data was
            // accessed; it does not carry any, and JdbcAuditTrail would otherwise wrap whatever is
            // passed here into after_state.
            assertThat(e.detail()).isNull();
        });
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a request that is not found is not audited as a view")
    void notFoundIsNotAView() {
        assertThatThrownBy(() -> useCase.detail(ACTOR, UUID.randomUUID()))
                .isInstanceOf(RequestDecision.NotFound.class);
        assertThat(audit.entries).isEmpty();
    }

    private static final class RecordingAudit
            implements com.pantropi.vms.application.identity.port.AuditTrail {
        record Entry(UUID actor, String action, String entityType, String entityId, String detail) {}

        final List<Entry> entries = new java.util.ArrayList<>();

        public void record(UUID actor, String action, String entityType, String entityId,
                           String detail) {
            entries.add(new Entry(actor, action, entityType, entityId, detail));
        }

        public void recordChange(UUID actor, String action, String entityType, String entityId,
                                 String before, String after) {
            throw new AssertionError("a read changes nothing; it must use record()");
        }

        public void recordSecurityDenial(UUID actor, String action, String permission, String route,
                                         String method, String outcome, String sourceIp) {
            throw new AssertionError("authorisation is decided at the boundary, not here");
        }
    }

    private static final class RecordingQueries implements VisitorRequestQueries {
        Filter lastFilter;
        Detail detail;

        public Page list(Filter filter) {
            lastFilter = filter;
            return new Page(List.of(), 0, filter.page().page(), filter.page().size());
        }

        public Optional<Detail> detail(UUID id) {
            return Optional.ofNullable(detail);
        }
    }
}
