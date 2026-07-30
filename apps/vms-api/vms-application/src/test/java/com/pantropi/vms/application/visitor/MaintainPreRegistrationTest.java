package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.IssuedCredentials;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.application.visitor.usecase.MaintainPreRegistration;
import com.pantropi.vms.application.visitor.usecase.RequestDecision;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import com.pantropi.vms.domain.visitor.VisitorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-08.1.3 · T-08.1.3.2 — pre-registration maintenance over fake ports.
 *
 * <p>The floor question is deliberately absent here: whose records a receptionist may touch is the
 * scope seam's answer inside the repository, exercised against the real database in the IT. What
 * this class owns is what happens once a record is theirs to touch.
 */
class MaintainPreRegistrationTest {

    private static final Instant NOW = Instant.parse("2030-05-30T10:15:00Z");
    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");
    private static final UUID RECEPTIONIST = UUID.randomUUID();

    private final FakeRepo repo = new FakeRepo();
    private final FakeTypes types = new FakeTypes();
    private final FakeCredentials credentials = new FakeCredentials();
    private final FakeEvents events = new FakeEvents();
    private final FakeAudit audit = new FakeAudit();
    private final CountingRunner tx = new CountingRunner();

    private final MaintainPreRegistration useCase = new MaintainPreRegistration(
            repo, types, credentials, events, audit, tx, () -> NOW);

    // ---- AC-1: amend ----

    @Test
    @DisplayName("AC-1: a partial amendment changes what it names and keeps the rest")
    void partialAmendmentCoalesces() {
        VisitorRequest request = repo.hold(preRegistration());
        UUID visitorId = request.visitors().get(0).id();

        useCase.amend(RECEPTIONIST, visitorId, new MaintainPreRegistration.Amendment(
                "Grace Hopper", null, null, null, null, null, null));

        Visitor amended = request.visitors().get(0);
        assertThat(amended.fullName()).isEqualTo("Grace Hopper");
        // Untouched fields survive: null meant "leave alone", not "clear".
        assertThat(amended.emailValue()).isEqualTo("ada@example.test");
        assertThat(amended.company()).isEqualTo("Analytical Ltd");
        assertThat(amended.id()).isEqualTo(visitorId);
    }

    @Test
    @DisplayName("AC-1: moving one end of the window keeps the other")
    void windowMovesPartially() {
        VisitorRequest request = repo.hold(preRegistration());
        UUID visitorId = request.visitors().get(0).id();
        Instant newFrom = WINDOW_FROM.plus(1, ChronoUnit.HOURS);

        useCase.amend(RECEPTIONIST, visitorId, new MaintainPreRegistration.Amendment(
                null, null, null, null, null, newFrom, null));

        assertThat(request.window().from()).isEqualTo(newFrom);
        assertThat(request.window().to()).isEqualTo(WINDOW_TO);
    }

    @Test
    @DisplayName("AC-1: the audit says what kind of change, never the content")
    void amendmentAuditCarriesFlagsNotContent() {
        VisitorRequest request = repo.hold(preRegistration());
        UUID visitorId = request.visitors().get(0).id();

        useCase.amend(RECEPTIONIST, visitorId, new MaintainPreRegistration.Amendment(
                "Grace Hopper", "grace@example.test", null, null, null, null, null));

        assertThat(audit.entries).singleElement().satisfies(e -> {
            assertThat(e.action()).isEqualTo("visitor.amend");
            assertThat(e.entityId()).isEqualTo(visitorId.toString());
            assertThat(e.after()).contains("\"detailsChanged\":true")
                    .contains("\"windowChanged\":false");
            assertThat(e.after()).doesNotContain("Grace Hopper")
                    .doesNotContain("grace@example.test");
        });
        assertThat(events.types()).containsExactly("VisitorPreRegistrationAmended");
    }

    @Test
    @DisplayName("an amendment that loses to a concurrent decision writes nothing")
    void amendmentLosingTheRaceWritesNothing() {
        VisitorRequest request = repo.hold(preRegistration());
        repo.writeLands = false;

        assertThatThrownBy(() -> useCase.amend(RECEPTIONIST, request.visitors().get(0).id(),
                new MaintainPreRegistration.Amendment("Grace", null, null, null, null, null, null)))
                .isInstanceOf(RequestDecision.DecidedElsewhere.class);

        assertThat(events.published).isEmpty();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("an unusable visitor type is refused before anything is written")
    void retiredTypeRefused() {
        VisitorRequest request = repo.hold(preRegistration());
        UUID retired = UUID.randomUUID();
        types.retired.add(retired);

        assertThatThrownBy(() -> useCase.amend(RECEPTIONIST, request.visitors().get(0).id(),
                new MaintainPreRegistration.Amendment(null, null, null, null, retired, null, null)))
                .isInstanceOf(SubmitVisitorRequest.UnknownVisitorType.class);

        assertThat(tx.calls).isZero();
    }

    @Test
    @DisplayName("AC-5's shape: an unknown visitor is not found, and nothing distinguishes it "
            + "from one outside the caller's scope")
    void unknownVisitorIsNotFound() {
        assertThatThrownBy(() -> useCase.amend(RECEPTIONIST, UUID.randomUUID(),
                new MaintainPreRegistration.Amendment("Grace", null, null, null, null, null, null)))
                .isInstanceOf(RequestDecision.NotFound.class);
    }

    // ---- AC-2, AC-3: cancel ----

    @Test
    @DisplayName("AC-2: cancelling the only visitor takes the request, and the outcome says so")
    void cancellingTheOnlyVisitorTakesTheRequest() {
        VisitorRequest request = repo.hold(preRegistration());
        UUID visitorId = request.visitors().get(0).id();

        MaintainPreRegistration.Outcome outcome = useCase.cancel(RECEPTIONIST, visitorId);

        assertThat(outcome.requestCancelled()).isTrue();
        assertThat(outcome.requestStatus()).isEqualTo("cancelled");
        assertThat(request.visitors().get(0).status()).isEqualTo(VisitorStatus.CANCELLED);
        assertThat(audit.entries).singleElement().satisfies(e ->
                assertThat(e.after()).contains("\"requestCancelled\":true"));
    }

    @Test
    @DisplayName("AC-3: no live credential means no revocation signal — asking to revoke nothing "
            + "would teach the consumer to ignore the signal")
    void noCredentialMeansNoRevocation() {
        VisitorRequest request = repo.hold(preRegistration());

        MaintainPreRegistration.Outcome outcome =
                useCase.cancel(RECEPTIONIST, request.visitors().get(0).id());

        assertThat(outcome.revocationRequested()).isFalse();
        assertThat(events.types()).containsExactly("VisitorCancelled");
        assertThat(audit.entries).singleElement().satisfies(e ->
                assertThat(e.after()).contains("\"followedIssuance\":false"));
    }

    @Test
    @DisplayName("AC-3: a live credential means exactly one revocation signal, in the same "
            + "transaction, naming this visitor alone")
    void liveCredentialMeansOneRevocation() {
        VisitorRequest request = repo.hold(preRegistration());
        UUID visitorId = request.visitors().get(0).id();
        credentials.live.add(visitorId);

        MaintainPreRegistration.Outcome outcome = useCase.cancel(RECEPTIONIST, visitorId);

        assertThat(outcome.revocationRequested()).isTrue();
        assertThat(events.types())
                .containsExactly("VisitorCancelled", "CredentialRevocationRequested");
        assertThat(events.payloadOf("CredentialRevocationRequested"))
                .contains(visitorId.toString())
                .contains("pre_registration_cancelled");
        // AC-3: the audit records that the cancellation followed issuance — the credential row
        // itself may be gone by the time anyone asks.
        assertThat(audit.entries).singleElement().satisfies(e ->
                assertThat(e.after()).contains("\"followedIssuance\":true"));
    }

    @Test
    @DisplayName("AC-3: a cancellation that loses the race revokes nothing")
    void losingCancellationRevokesNothing() {
        // The dangerous half of AC-3: a revocation from a cancellation that never landed would
        // strand a legitimate visitor with a dead pass.
        VisitorRequest request = repo.hold(preRegistration());
        UUID visitorId = request.visitors().get(0).id();
        credentials.live.add(visitorId);
        repo.writeLands = false;

        assertThatThrownBy(() -> useCase.cancel(RECEPTIONIST, visitorId))
                .isInstanceOf(RequestDecision.DecidedElsewhere.class);

        assertThat(events.published).isEmpty();
    }

    @Test
    @DisplayName("AC-4 reaches this layer untouched: a checked-in visitor's refusal passes through")
    void checkedInRefusalPassesThrough() {
        // Rehydrated in the checked-in state, as the repository would load it — moveTo is the
        // aggregate's own and rightly unreachable from here.
        Visitor checkedIn = Visitor.rehydrate(UUID.randomUUID(), "Ada Lovelace",
                "ada@example.test", null, null, null, VisitorStatus.CHECKED_IN);
        VisitorRequest request = repo.hold(VisitorRequest.rehydrate(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), RECEPTIONIST,
                com.pantropi.vms.domain.visitor.VisitKind.PRE_SCHEDULED,
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Site inspection",
                List.of(checkedIn), RequestStatus.APPROVED, null, null, null));

        assertThatThrownBy(() -> useCase.cancel(RECEPTIONIST, checkedIn.id()))
                .isInstanceOf(VisitorRequest.VisitorNotEditable.class);

        assertThat(tx.calls).isZero();
        assertThat(events.published).isEmpty();
    }

    // ---- fakes ----

    private static VisitorRequest preRegistration() {
        return VisitorRequest.submit(UUID.randomUUID(), UUID.randomUUID(), RECEPTIONIST,
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Site inspection",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", "+8801712345678",
                        "Analytical Ltd", null)));
    }

    private static final class FakeRepo implements VisitorRequestRepository {
        VisitorRequest held;
        boolean writeLands = true;

        VisitorRequest hold(VisitorRequest r) {
            held = r;
            return r;
        }

        public void save(VisitorRequest r) {
            held = r;
        }

        public Optional<VisitorRequest> findById(UUID id) {
            return Optional.ofNullable(held);
        }

        public Optional<VisitorRequest> findByVisitorId(UUID visitorId) {
            if (held == null) {
                return Optional.empty();
            }
            boolean mine = held.visitors().stream().anyMatch(v -> v.id().equals(visitorId));
            return mine ? Optional.of(held) : Optional.empty();
        }

        public boolean saveDecision(VisitorRequest r, RequestStatus expected) {
            return writeLands;
        }

        public boolean saveAmendment(VisitorRequest r, RequestStatus expected) {
            return writeLands;
        }

        public boolean savePreArrivalChange(VisitorRequest r, RequestStatus expected) {
            return writeLands;
        }
    }

    private static final class FakeTypes implements VisitorTypeDirectory {
        final java.util.Set<UUID> retired = new java.util.HashSet<>();

        public boolean isSelectable(UUID id) {
            return id != null && !retired.contains(id);
        }
    }

    private static final class FakeCredentials implements IssuedCredentials {
        final java.util.Set<UUID> live = new java.util.HashSet<>();

        public boolean anyLiveFor(UUID visitorId) {
            return live.contains(visitorId);
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
        record Entry(String action, String entityId, String before, String after) {}

        final List<Entry> entries = new ArrayList<>();

        public void recordChange(UUID actor, String action, String entityType, String entityId,
                                 String before, String after) {
            entries.add(new Entry(action, entityId, before, after));
        }

        public void record(UUID actor, String action, String entityType, String entityId,
                           String detail) {
            throw new AssertionError("maintenance changes state; it must use recordChange");
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
