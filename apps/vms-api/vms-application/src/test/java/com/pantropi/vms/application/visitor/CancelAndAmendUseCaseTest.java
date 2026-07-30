package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.application.visitor.usecase.AmendVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.CancelVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.RequestDecision;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.RequestTransitions;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
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
 * US-07.1.3 · T-07.1.3.1/2 — the amend and cancel use cases over fake ports.
 */
class CancelAndAmendUseCaseTest {

    private static final Instant NOW = Instant.parse("2030-05-30T10:15:00Z");
    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");

    private final FakeRepo repo = new FakeRepo();
    private final FakeEvents events = new FakeEvents();
    private final FakeAudit audit = new FakeAudit();
    private final CountingRunner tx = new CountingRunner();
    private final FakeTenants tenants = new FakeTenants();
    private final FakeTypes types = new FakeTypes();

    private final CancelVisitorRequest cancel =
            new CancelVisitorRequest(repo, events, audit, tx, () -> NOW);
    private final AmendVisitorRequest amend =
            new AmendVisitorRequest(repo, tenants, types, events, audit, tx, () -> NOW);

    // ---- AC-2, AC-3: cancellation ----

    @Test
    @DisplayName("AC-2: cancelling a submitted request emits one event and no revocation signal")
    void cancelBeforeApproval() {
        VisitorRequest request = repo.hold(submitted());
        UUID actor = UUID.randomUUID();

        RequestDecision result = cancel.cancel(actor, request.id());

        assertThat(result.status()).isEqualTo("cancelled");
        assertThat(repo.expected).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(events.types()).containsExactly("VisitorRequestCancelled");
        // Nothing was ever granted, so asking for a revocation would be asking to revoke nothing.
        assertThat(events.types()).doesNotContain("CredentialRevocationRequested");
        assertThat(audit.entries).singleElement()
                .satisfies(e -> assertThat(e.after()).contains("\"followedApproval\":false"));
    }

    @Test
    @DisplayName("AC-3: cancelling an approved request also asks for the credential to be revoked")
    void cancelAfterApproval() {
        VisitorRequest request = repo.hold(submitted());
        request.approve(UUID.randomUUID(), null, NOW);

        cancel.cancel(UUID.randomUUID(), request.id());

        assertThat(events.types())
                .containsExactly("VisitorRequestCancelled", "CredentialRevocationRequested");
        assertThat(repo.expected).isEqualTo(RequestStatus.APPROVED);
        // AC-3: the audit records that the cancellation followed an approval.
        assertThat(audit.entries).singleElement()
                .satisfies(e -> assertThat(e.after()).contains("\"followedApproval\":true"));

        String revocation = events.payloadOf("CredentialRevocationRequested");
        for (UUID visitorId : request.visitorIds()) {
            assertThat(revocation).contains(visitorId.toString());
        }
        assertThat(revocation).doesNotContain("Ada Lovelace").doesNotContain("ada@example.test");
    }

    @Test
    @DisplayName("AC-5: losing to a concurrent approval writes nothing — no spurious revocation")
    void cancelLosingTheRace() {
        // The dangerous failure this guards: a losing cancellation that still emitted the revocation
        // signal would revoke the credential of a visit that is actually going ahead.
        VisitorRequest request = repo.hold(submitted());
        repo.writeLands = false;

        assertThatThrownBy(() -> cancel.cancel(UUID.randomUUID(), request.id()))
                .isInstanceOf(RequestDecision.DecidedElsewhere.class);

        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("AC-4: cancelling a rejected request is refused before the transaction opens")
    void cancelFromTerminalState() {
        VisitorRequest request = repo.hold(submitted());
        request.reject(UUID.randomUUID(), "Host on leave", NOW);

        assertThatThrownBy(() -> cancel.cancel(UUID.randomUUID(), request.id()))
                .isInstanceOf(RequestTransitions.IllegalTransition.class);

        assertThat(tx.calls).isZero();
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("AC-6: another tenant's request is simply not found")
    void cancelNotFound() {
        assertThatThrownBy(() -> cancel.cancel(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(RequestDecision.NotFound.class);
        assertThat(events.published).isEmpty();
    }

    // ---- AC-1: amendment ----

    @Test
    @DisplayName("AC-1: an amendment persists, is audited by field, and publishes one event")
    void amendPersists() {
        VisitorRequest request = repo.hold(submitted());
        UUID actor = UUID.randomUUID();

        amend.amend(actor, request.id(), new AmendVisitorRequest.Amendment(
                tenants.validHost, WINDOW_FROM.plusSeconds(3600), null, "Rescheduled", null));

        assertThat(request.purpose()).isEqualTo("Rescheduled");
        assertThat(request.window().from()).isEqualTo(WINDOW_FROM.plusSeconds(3600));
        // Only one end was supplied, so the other is kept rather than nulled.
        assertThat(request.window().to()).isEqualTo(WINDOW_TO);

        assertThat(events.types()).containsExactly("VisitorRequestAmended");
        assertThat(audit.entries).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo("visitor_request.amend");
            assertThat(e.after()).contains("\"purposeChanged\":true")
                    .contains("\"windowChanged\":true")
                    .contains("\"visitorsReplaced\":false");
            assertThat(e.after()).doesNotContain("Ada Lovelace").doesNotContain("ada@example.test");
        });
    }

    @Test
    @DisplayName("AC-1: replacing the visitors applies the same rules as submitting them")
    void amendedVisitorsAreValidated() {
        VisitorRequest request = repo.hold(submitted());

        amend.amend(UUID.randomUUID(), request.id(), new AmendVisitorRequest.Amendment(
                null, null, null, null,
                List.of(new SubmitVisitorRequest.VisitorDetail("Grace Hopper",
                        "  GRACE@Example.TEST ", "+880 1712-345678", null, null))));

        assertThat(request.visitors()).singleElement().satisfies(v -> {
            assertThat(v.fullName()).isEqualTo("Grace Hopper");
            // Normalised by the same value objects as on submission (US-07.1.2 AC-2).
            assertThat(v.emailValue()).isEqualTo("grace@example.test");
            assertThat(v.phoneValue()).isEqualTo("+8801712345678");
        });
    }

    @Test
    @DisplayName("a host outside the request's own tenant is refused, and nothing is written")
    void amendWithForeignHostRefused() {
        VisitorRequest request = repo.hold(submitted());

        assertThatThrownBy(() -> amend.amend(UUID.randomUUID(), request.id(),
                new AmendVisitorRequest.Amendment(UUID.randomUUID(), null, null, "x", null)))
                .isInstanceOf(SubmitVisitorRequest.HostNotInTenant.class);

        assertThat(request.purpose()).isEqualTo("Quarterly review");
        assertThat(tx.calls).isZero();
    }

    @Test
    @DisplayName("an unusable visitor type is refused, and nothing is written")
    void amendWithRetiredTypeRefused() {
        VisitorRequest request = repo.hold(submitted());
        UUID retired = UUID.randomUUID();
        types.retired.add(retired);

        assertThatThrownBy(() -> amend.amend(UUID.randomUUID(), request.id(),
                new AmendVisitorRequest.Amendment(null, null, null, null,
                        List.of(new SubmitVisitorRequest.VisitorDetail("Grace", null, null, null,
                                retired)))))
                .isInstanceOf(SubmitVisitorRequest.UnknownVisitorType.class);

        assertThat(request.visitors()).hasSize(2);
        assertThat(tx.calls).isZero();
    }

    @Test
    @DisplayName("AC-4: amending a decided request is refused before anything is written")
    void amendDecidedRefused() {
        VisitorRequest request = repo.hold(submitted());
        request.approve(UUID.randomUUID(), null, NOW);

        assertThatThrownBy(() -> amend.amend(UUID.randomUUID(), request.id(),
                new AmendVisitorRequest.Amendment(null, null, null, "Sneaky", null)))
                .isInstanceOf(VisitorRequest.RequestNotPending.class);

        assertThat(tx.calls).isZero();
        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("AC-5: an amendment that loses to a decision writes nothing")
    void amendLosingTheRace() {
        VisitorRequest request = repo.hold(submitted());
        repo.writeLands = false;

        assertThatThrownBy(() -> amend.amend(UUID.randomUUID(), request.id(),
                new AmendVisitorRequest.Amendment(null, null, null, "Too late", null)))
                .isInstanceOf(RequestDecision.DecidedElsewhere.class);

        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("the amendment command has no field that could set a status or a tenant")
    void amendmentCannotEscalate() {
        assertThat(AmendVisitorRequest.Amendment.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("tenantId", "status", "approvedBy", "decisionReason");
    }

    // ---- fakes ----

    private static VisitorRequest submitted() {
        return VisitorRequest.submit(UUID.randomUUID(), FakeTenants.HOST, UUID.randomUUID(),
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Quarterly review",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", null, null, null),
                        Visitor.named("Alan Turing", "alan@example.test", null, null, null)));
    }

    private static final class FakeRepo implements VisitorRequestRepository {
        VisitorRequest held;
        RequestStatus expected;
        boolean writeLands = true;

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

        public boolean saveDecision(VisitorRequest r, RequestStatus expectedPrevious) {
            expected = expectedPrevious;
            return writeLands;
        }

        public boolean saveAmendment(VisitorRequest r, RequestStatus expectedCurrent) {
            expected = expectedCurrent;
            return writeLands;
        }

        public Optional<VisitorRequest> findByVisitorId(UUID visitorId) {
            return Optional.ofNullable(held);
        }

        public boolean savePreArrivalChange(VisitorRequest r, RequestStatus expectedCurrent) {
            expected = expectedCurrent;
            return writeLands;
        }
    }

    private static final class FakeTenants implements TenantDirectory {
        static final UUID HOST = UUID.randomUUID();
        final UUID validHost = HOST;

        public Optional<UUID> tenantOfUser(UUID userId) {
            return Optional.of(UUID.randomUUID());
        }

        public boolean hostBelongsToTenant(UUID hostId, UUID tenantId) {
            return HOST.equals(hostId);
        }
    }

    private static final class FakeTypes implements VisitorTypeDirectory {
        final java.util.Set<UUID> retired = new java.util.HashSet<>();

        public boolean isSelectable(UUID id) {
            return id != null && !retired.contains(id);
        }
    }

    private static final class FakeEvents implements DomainEventPublisher {
        record Event(String type, String payload) {}

        final List<Event> published = new ArrayList<>();

        public void publish(String type, String aggregateType, UUID id, String payload) {
            published.add(new Event(type, payload));
        }

        List<String> types() {
            return published.stream().map(Event::type).toList();
        }

        String payloadOf(String type) {
            return published.stream().filter(e -> e.type().equals(type))
                    .map(Event::payload).findFirst().orElseThrow();
        }
    }

    private static final class FakeAudit implements AuditTrail {
        record Entry(String action, String before, String after) {}

        final List<Entry> entries = new ArrayList<>();

        public void recordChange(UUID actor, String action, String entityType, String entityId,
                                 String before, String after) {
            entries.add(new Entry(action, before, after));
        }

        public void record(UUID actor, String action, String entityType, String entityId,
                           String detail) {
            throw new AssertionError("these are state changes; they must use recordChange");
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
