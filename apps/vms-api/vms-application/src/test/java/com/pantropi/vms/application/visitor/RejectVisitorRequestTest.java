package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.usecase.RejectVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.RequestDecision;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.RequestTransitions;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import com.pantropi.vms.domain.visitor.VisitorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-07.4.2 · T-07.4.2.2 — the rejection use case over fake ports. No Spring, no database.
 */
class RejectVisitorRequestTest {

    private static final Instant NOW = Instant.parse("2030-05-30T10:15:00Z");
    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");

    private final FakeRepo repo = new FakeRepo();
    private final FakeEvents events = new FakeEvents();
    private final FakeAudit audit = new FakeAudit();
    private final CountingRunner tx = new CountingRunner();
    private final RejectVisitorRequest useCase =
            new RejectVisitorRequest(repo, events, audit, tx, () -> NOW);

    // ---- AC-1, AC-3 ----

    @Test
    @DisplayName("AC-1: the decision is persisted with the decider, the time and the reason")
    void rejectionIsPersisted() {
        VisitorRequest request = repo.hold(submitted());
        UUID decider = UUID.randomUUID();

        RequestDecision decision = useCase.reject(decider, request.id(), "Host is on leave");

        assertThat(decision.status()).isEqualTo("rejected");
        assertThat(decision.decidedBy()).isEqualTo(decider);
        assertThat(decision.decidedAt()).isEqualTo(NOW);
        assertThat(decision.note()).isEqualTo("Host is on leave");

        assertThat(repo.expectedPrevious).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.CANCELLED));
    }

    @Test
    @DisplayName("AC-3: the audit records the decider and the reason — unlike an approval's note")
    void auditCarriesTheReason() {
        VisitorRequest request = repo.hold(submitted());
        UUID decider = UUID.randomUUID();

        useCase.reject(decider, request.id(), "Host is on leave that week");

        assertThat(audit.entries).hasSize(1);
        FakeAudit.Entry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo("visitor_request.reject");
        assertThat(entry.actor()).isEqualTo(decider);
        assertThat(entry.before()).contains("submitted");
        // A refusal has to be defensible after the fact, and this is the only immutable record of
        // the grounds. That is why the reason is here when an approval note is not.
        assertThat(entry.after()).contains("rejected").contains("Host is on leave that week");
        assertThat(entry.after()).doesNotContain("Ada Lovelace").doesNotContain("ada@example.test");
    }

    @Test
    @DisplayName("AC-6: a reason containing quotes, backslashes and newlines still yields valid JSON")
    void auditPayloadStaysValidJson() {
        // The payload is stored as jsonb. Before JsonText, a reason with a raw newline — which is
        // what a textarea produces — made the insert fail on a perfectly ordinary rejection.
        VisitorRequest request = repo.hold(submitted());

        useCase.reject(UUID.randomUUID(), request.id(),
                "Refused because:\n  - \"no host\"\n  - path C:\\temp <script>alert(1)</script>");

        String after = audit.entries.get(0).after();
        assertThat(after).contains("\\n").contains("\\\"no host\\\"").contains("C:\\\\temp");
        // Stored, not sanitised: encoding belongs at render time, in the tenant's browser.
        assertThat(after).contains("<script>alert(1)</script>");
        assertThat(after).doesNotContain("\n").doesNotContain("\r");
    }

    // ---- AC-3, AC-4: the event ----

    @Test
    @DisplayName("AC-3/AC-4: one rejection event, carrying ids only, and nothing EPIC-09 consumes")
    void oneEventAndNothingForCredentials() {
        VisitorRequest request = repo.hold(submitted());

        useCase.reject(UUID.randomUUID(), request.id(), "Host is on leave");

        assertThat(events.published).hasSize(1);
        FakeEvents.Event event = events.published.get(0);
        assertThat(event.type()).isEqualTo("VisitorRequestRejected");
        // AC-4: rejection is a terminal branch — nothing on this path triggers credential creation.
        assertThat(events.published).noneMatch(e -> e.type().contains("Approved")
                || e.type().contains("Credential"));

        for (UUID visitorId : request.visitorIds()) {
            assertThat(event.payload()).contains(visitorId.toString());
        }
        // The reason stays out: an event is the one artefact that leaves for a message bus.
        assertThat(event.payload()).doesNotContain("Host is on leave")
                .doesNotContain("Ada Lovelace").doesNotContain("ada@example.test");
    }

    @Test
    @DisplayName("the write, the audit and the event all happen inside one transaction")
    void oneTransaction() {
        VisitorRequest request = repo.hold(submitted());

        useCase.reject(UUID.randomUUID(), request.id(), "Host is on leave");

        assertThat(tx.calls).isEqualTo(1);
        assertThat(repo.savedInsideTransaction).isTrue();
        assertThat(events.publishedInsideTransaction).isTrue();
        assertThat(audit.recordedInsideTransaction).isTrue();
    }

    // ---- AC-2 ----

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t\n"})
    @DisplayName("AC-2: a missing or blank reason is refused before the transaction opens")
    void reasonIsMandatory(String blank) {
        VisitorRequest request = repo.hold(submitted());

        assertThatThrownBy(() -> useCase.reject(UUID.randomUUID(), request.id(), blank))
                .isInstanceOf(VisitorRequest.RejectionReasonRequired.class);

        assertThat(request.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(tx.calls).isZero();
        assertThat(repo.saveCalls).isZero();
        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    // ---- AC-5 and the concurrent decision ----

    @Test
    @DisplayName("AC-5: rejecting an approved request is refused and writes nothing")
    void approvedCannotBeRejected() {
        VisitorRequest request = repo.hold(submitted());
        request.approve(UUID.randomUUID(), null, NOW);

        assertThatThrownBy(() -> useCase.reject(UUID.randomUUID(), request.id(), "On reflection"))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(repo.saveCalls).isZero();
        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("losing the race yields a conflict, and no event and no audit entry survive")
    void concurrentDecisionLoses() {
        VisitorRequest request = repo.hold(submitted());
        repo.decisionLands = false;

        assertThatThrownBy(() -> useCase.reject(UUID.randomUUID(), request.id(), "Host on leave"))
                .isInstanceOf(RequestDecision.DecidedElsewhere.class);

        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("a request that is absent — or out of scope — is not found, and nothing is written")
    void notFound() {
        assertThatThrownBy(() ->
                useCase.reject(UUID.randomUUID(), UUID.randomUUID(), "Host on leave"))
                .isInstanceOf(RequestDecision.NotFound.class);

        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    // ---- fakes ----

    private static VisitorRequest submitted() {
        return VisitorRequest.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Quarterly review",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", null, null, null),
                        Visitor.named("Alan Turing", "alan@example.test", null, null, null)));
    }

    private static final class FakeRepo implements VisitorRequestRepository {
        VisitorRequest held;
        RequestStatus expectedPrevious;
        boolean decisionLands = true;
        boolean savedInsideTransaction;
        int saveCalls;

        VisitorRequest hold(VisitorRequest r) {
            held = r;
            return r;
        }

        public void save(VisitorRequest r) {
            held = r;
        }

        public Optional<VisitorRequest> findById(UUID id) {
            return held != null && held.id().equals(id) ? Optional.of(held) : Optional.empty();
        }

        public boolean saveDecision(VisitorRequest r, RequestStatus expected) {
            saveCalls++;
            expectedPrevious = expected;
            savedInsideTransaction = true;
            return decisionLands;
        }
        public boolean saveAmendment(VisitorRequest r, RequestStatus expected) {
            saveCalls++;
            expectedPrevious = expected;
            savedInsideTransaction = true;
            return decisionLands;
        }
    }

    private static final class FakeEvents implements DomainEventPublisher {
        record Event(String type, String aggregateType, UUID id, String payload) {}

        final List<Event> published = new ArrayList<>();
        boolean publishedInsideTransaction;

        public void publish(String type, String aggregateType, UUID id, String payload) {
            published.add(new Event(type, aggregateType, id, payload));
            publishedInsideTransaction = true;
        }
    }

    private static final class FakeAudit implements AuditTrail {
        record Entry(UUID actor, String action, String entityType, String entityId, String before,
                     String after) {}

        final List<Entry> entries = new ArrayList<>();
        boolean recordedInsideTransaction;

        public void recordChange(UUID actor, String action, String entityType, String entityId,
                                 String before, String after) {
            entries.add(new Entry(actor, action, entityType, entityId, before, after));
            recordedInsideTransaction = true;
        }

        public void record(UUID actor, String action, String entityType, String entityId,
                           String detail) {
            throw new AssertionError("a rejection is a state change; it must use recordChange");
        }

        public void recordSecurityDenial(UUID actor, String action, String permission, String route,
                                         String method, String outcome, String sourceIp) {
            throw new AssertionError("authorisation is decided at the boundary, not here");
        }
    }

    private static final class CountingRunner implements TransactionRunner {
        int calls;

        public <T> T call(Supplier<T> work) {
            calls++;
            return work.get();
        }
    }
}
