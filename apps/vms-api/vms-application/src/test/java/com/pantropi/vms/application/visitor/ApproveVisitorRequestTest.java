package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.usecase.ApproveVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.RequestDecision;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.RequestTransitions;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import com.pantropi.vms.domain.visitor.VisitorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-07.4.1 · T-07.4.1.2 — the approval use case over fake ports. No Spring, no database.
 *
 * <p>The point of interest is AC-6: the event must be published exactly once, and only by the caller
 * whose decision actually landed. That is asserted here by making the repository refuse the write —
 * which is what a real database does to the loser of a race — and checking that nothing else was
 * emitted.
 */
class ApproveVisitorRequestTest {

    private static final Instant NOW = Instant.parse("2030-05-30T10:15:00Z");
    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");

    private final FakeRepo repo = new FakeRepo();
    private final FakeEvents events = new FakeEvents();
    private final FakeAudit audit = new FakeAudit();
    private final CountingRunner tx = new CountingRunner();
    private final ApproveVisitorRequest useCase =
            new ApproveVisitorRequest(repo, events, audit, tx, () -> NOW);

    // ---- AC-1, AC-2, AC-3, AC-4 on the happy path ----

    @Test
    @DisplayName("AC-1/AC-4: the decision is persisted with the approver, the time and the note")
    void approvalIsPersisted() {
        VisitorRequest request = repo.hold(submitted());
        UUID approver = UUID.randomUUID();

        RequestDecision decision =
                useCase.approve(approver, request.id(), "Cleared with security");

        assertThat(decision.status()).isEqualTo("approved");
        assertThat(decision.decidedBy()).isEqualTo(approver);
        assertThat(decision.decidedAt()).isEqualTo(NOW);
        assertThat(decision.note()).isEqualTo("Cleared with security");

        assertThat(repo.expectedPrevious).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(request.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(request.visitors()).allSatisfy(
                v -> assertThat(v.status()).isEqualTo(VisitorStatus.APPROVED));
    }

    @Test
    @DisplayName("AC-2: the event carries ids, times and the tenant — and no visitor personal data")
    void eventCarriesNoPii() {
        VisitorRequest request = repo.hold(submitted());

        useCase.approve(UUID.randomUUID(), request.id(), null);

        assertThat(events.published).hasSize(1);
        FakeEvents.Event event = events.published.get(0);
        assertThat(event.type()).isEqualTo("VisitorRequestApproved");
        assertThat(event.aggregateType()).isEqualTo("visitor_request");
        assertThat(event.id()).isEqualTo(request.id());

        // Every visitor id is present so EPIC-09 knows who to issue credentials for (AC-2)...
        for (UUID visitorId : request.visitorIds()) {
            assertThat(event.payload()).contains(visitorId.toString());
        }
        assertThat(event.payload())
                .contains(WINDOW_FROM.toString())
                .contains(WINDOW_TO.toString())
                // ...and nothing that identifies them as people.
                .doesNotContain("Ada Lovelace")
                .doesNotContain("Alan Turing")
                .doesNotContain("ada@example.test")
                .doesNotContain("alan@example.test");
    }

    @Test
    @DisplayName("AC-3: the audit records the before and after states without PII or the note text")
    void auditRecordsTheChange() {
        VisitorRequest request = repo.hold(submitted());
        UUID approver = UUID.randomUUID();

        useCase.approve(approver, request.id(), "Escorted by the host throughout");

        assertThat(audit.entries).hasSize(1);
        FakeAudit.Entry entry = audit.entries.get(0);
        assertThat(entry.action()).isEqualTo("visitor_request.approve");
        assertThat(entry.actor()).isEqualTo(approver);
        assertThat(entry.entityId()).isEqualTo(request.id().toString());
        assertThat(entry.before()).contains("submitted");
        assertThat(entry.after()).contains("approved").contains(approver.toString());
        // US-07.4.3 AC-1 asks the audit row to carry "the decision reason where applicable", which
        // supersedes US-07.4.1's choice to record only that a note was given. An approval note is a
        // decision reason, so the trail now holds it — the same field, for the same purpose, as a
        // rejection reason.
        assertThat(entry.after()).contains("Escorted by the host throughout");
        assertThat(entry.before()).doesNotContain("Ada Lovelace");
        assertThat(entry.after()).doesNotContain("Ada Lovelace");
    }

    @Test
    @DisplayName("the write, the audit and the event all happen inside one transaction")
    void oneTransaction() {
        VisitorRequest request = repo.hold(submitted());

        useCase.approve(UUID.randomUUID(), request.id(), null);

        assertThat(tx.calls).isEqualTo(1);
        assertThat(repo.savedInsideTransaction).isTrue();
        assertThat(events.publishedInsideTransaction).isTrue();
        assertThat(audit.recordedInsideTransaction).isTrue();
    }

    // ---- AC-6: the losing caller ----

    @Test
    @DisplayName("AC-6: losing the race yields a conflict, and no event and no audit entry survive")
    void concurrentApprovalLoses() {
        VisitorRequest request = repo.hold(submitted());
        repo.decisionLands = false;       // the database refuses: someone decided it first

        assertThatThrownBy(() -> useCase.approve(UUID.randomUUID(), request.id(), null))
                .isInstanceOf(RequestDecision.DecidedElsewhere.class);

        // The loser must contribute nothing. In production the transaction rolls these back; here
        // the assertion is stronger — it never got as far as writing them.
        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("AC-6: two callers deciding the same request produce exactly one event")
    void exactlyOneEventForTwoApprovers() {
        // Both load their own copy, as two application instances would. The repository lets the
        // first write through and refuses the second, which is what the compare-and-set does.
        VisitorRequest first = submitted();
        VisitorRequest second = VisitorRequest.rehydrate(first.id(), first.tenantId(),
                first.hostId(), first.requestedBy(), first.visitKind(), first.window(),
                first.purpose(), first.visitors(), RequestStatus.SUBMITTED, null, null, null);
        repo.hold(first);

        useCase.approve(UUID.randomUUID(), first.id(), null);

        repo.hold(second);
        repo.decisionLands = false;
        assertThatThrownBy(() -> useCase.approve(UUID.randomUUID(), second.id(), null))
                .isInstanceOf(RequestDecision.DecidedElsewhere.class);

        assertThat(events.published).hasSize(1);
        assertThat(audit.entries).hasSize(1);
    }

    // ---- AC-5, AC-7 and the missing request ----

    @Test
    @DisplayName("AC-5: an already-decided request is refused before anything is written")
    void alreadyDecided() {
        VisitorRequest request = repo.hold(submitted());
        request.approve(UUID.randomUUID(), null, NOW);
        repo.saveCalls = 0;

        assertThatThrownBy(() -> useCase.approve(UUID.randomUUID(), request.id(), null))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(repo.saveCalls).isZero();
        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
        assertThat(tx.calls).isZero();      // the transaction is never even opened
    }

    @Test
    @DisplayName("AC-7: an elapsed window is refused before anything is written")
    void elapsedWindow() {
        ApproveVisitorRequest late = new ApproveVisitorRequest(repo, events, audit, tx,
                () -> WINDOW_TO.plusSeconds(1));
        VisitorRequest request = repo.hold(submitted());

        assertThatThrownBy(() -> late.approve(UUID.randomUUID(), request.id(), null))
                .isInstanceOf(VisitorRequest.WindowAlreadyElapsed.class);

        assertThat(repo.saveCalls).isZero();
        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("a request that is absent — or out of scope — is not found, and nothing is written")
    void notFound() {
        assertThatThrownBy(() -> useCase.approve(UUID.randomUUID(), UUID.randomUUID(), null))
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
            throw new AssertionError("an approval is a state change; it must use recordChange");
        }

        public void recordSecurityDenial(UUID actor, String action, String permission, String route,
                                         String method, String outcome, String sourceIp) {
            throw new AssertionError("authorisation is decided at the boundary, not in this "
                    + "use case");
        }
    }

    /** Records that the work ran, so "inside the transaction" is asserted rather than assumed. */
    private static final class CountingRunner implements TransactionRunner {
        int calls;

        public <T> T call(Supplier<T> work) {
            calls++;
            return work.get();
        }
    }
}
