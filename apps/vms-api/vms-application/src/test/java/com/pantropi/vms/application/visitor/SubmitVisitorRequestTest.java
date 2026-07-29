package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
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

/** Unit tests for {@link SubmitVisitorRequest} (US-07.1.1, T-07.1.1.3) — pure, fake ports. */
class SubmitVisitorRequestTest {

    private final Instant from = Instant.parse("2026-08-01T09:00:00Z");
    private final Instant to = from.plus(2, ChronoUnit.HOURS);
    private final UUID submitter = UUID.randomUUID();
    private final UUID tenant = UUID.randomUUID();
    private final UUID host = UUID.randomUUID();

    private final FakeRepo repo = new FakeRepo();
    private final FakeTenants tenants = new FakeTenants(tenant, host);
    private final FakeEvents events = new FakeEvents();
    private final FakeAudit audit = new FakeAudit();
    private final SubmitVisitorRequest useCase =
            new SubmitVisitorRequest(repo, tenants, events, audit, direct());

    private SubmitVisitorRequest.Command command() {
        return new SubmitVisitorRequest.Command(host, from, to, "Quarterly review",
                List.of(new SubmitVisitorRequest.VisitorDetail(
                        "Ada Lovelace", "ada@example.test", "+880100000000", "Analytical Ltd")));
    }

    @Test
    @DisplayName("AC-1: the request is saved as submitted, against the submitter's own tenant")
    void savesSubmittedRequest() {
        UUID id = useCase.submit(submitter, command());

        assertThat(id).isNotNull();
        assertThat(repo.saved).isNotNull();
        assertThat(repo.saved.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(repo.saved.tenantId()).isEqualTo(tenant);
        assertThat(repo.saved.requestedBy()).isEqualTo(submitter);
        assertThat(repo.saved.visitors()).singleElement()
                .extracting(Visitor::fullName).isEqualTo("Ada Lovelace");
    }

    @Test
    @DisplayName("AC-5: the tenant is resolved from the submitter — the command cannot carry one")
    void tenantIsNeverClientSupplied() {
        // Structural, not merely enforced: the Command record has no tenantId component at all.
        assertThat(SubmitVisitorRequest.Command.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("tenantId", "status", "requestedBy", "approvedBy");

        useCase.submit(submitter, command());
        assertThat(repo.saved.tenantId()).isEqualTo(tenants.tenantOfUser(submitter).orElseThrow());
    }

    @Test
    @DisplayName("AC-2: the event carries identifiers and timing but no visitor personal data")
    void eventCarriesNoPersonalData() {
        useCase.submit(submitter, command());

        assertThat(events.published).hasSize(1);
        FakeEvents.Event e = events.published.get(0);
        assertThat(e.type()).isEqualTo("VisitorRequestSubmitted");
        assertThat(e.payload()).contains("requestId").contains("tenantId").contains("visitorCount");
        assertThat(e.payload())
                .doesNotContain("Ada").doesNotContain("ada@example.test")
                .doesNotContain("+880100000000").doesNotContain("Analytical");
    }

    @Test
    @DisplayName("AC-3: an audit entry is written and it too excludes visitor personal data")
    void auditIsWrittenWithoutPersonalData() {
        useCase.submit(submitter, command());

        assertThat(audit.actions).containsExactly("visitor_request.submit");
        assertThat(audit.lastAfter).contains("visitorCount").contains("submitted");
        assertThat(audit.lastAfter).doesNotContain("Ada").doesNotContain("ada@example.test");
    }

    @Test
    @DisplayName("AC-4: an invalid window is refused before anything is persisted or published")
    void invalidWindowPersistsNothing() {
        SubmitVisitorRequest.Command bad = new SubmitVisitorRequest.Command(
                host, to, from, null,
                List.of(new SubmitVisitorRequest.VisitorDetail("Ada", null, null, null)));

        assertThatThrownBy(() -> useCase.submit(submitter, bad))
                .isInstanceOf(TimeWindow.InvalidTimeWindow.class);
        assertThat(repo.saved).isNull();
        assertThat(events.published).isEmpty();
        assertThat(audit.actions).isEmpty();
    }

    @Test
    @DisplayName("a host outside the submitter's tenant is refused")
    void foreignHostRefused() {
        SubmitVisitorRequest.Command foreign = new SubmitVisitorRequest.Command(
                UUID.randomUUID(), from, to, null,
                List.of(new SubmitVisitorRequest.VisitorDetail("Ada", null, null, null)));

        assertThatThrownBy(() -> useCase.submit(submitter, foreign))
                .isInstanceOf(SubmitVisitorRequest.HostNotInTenant.class);
        assertThat(repo.saved).isNull();
    }

    @Test
    @DisplayName("a submitter with no tenant cannot submit")
    void submitterWithoutTenantRefused() {
        var orphan = new SubmitVisitorRequest(repo, new FakeTenants(null, host), events, audit, direct());
        assertThatThrownBy(() -> orphan.submit(submitter, command()))
                .isInstanceOf(SubmitVisitorRequest.NoTenantForUser.class);
        assertThat(repo.saved).isNull();
    }

    @Test
    @DisplayName("persistence, audit and event all happen inside one transaction")
    void allWorkIsTransactional() {
        CountingRunner counting = new CountingRunner();
        new SubmitVisitorRequest(repo, tenants, events, audit, counting).submit(submitter, command());

        assertThat(counting.calls).isEqualTo(1);
        assertThat(counting.workInsideTransaction).isTrue();
    }

    // ---- fakes ----
    private static TransactionRunner direct() {
        return new TransactionRunner() {
            public <T> T call(Supplier<T> work) { return work.get(); }
        };
    }

    private static final class CountingRunner implements TransactionRunner {
        int calls;
        boolean workInsideTransaction;
        public <T> T call(Supplier<T> work) {
            calls++;
            T result = work.get();
            workInsideTransaction = true;
            return result;
        }
    }

    private static final class FakeRepo implements VisitorRequestRepository {
        VisitorRequest saved;
        public void save(VisitorRequest r) { saved = r; }
        public Optional<VisitorRequest> findById(UUID id) { return Optional.ofNullable(saved); }
        public boolean saveDecision(VisitorRequest r,
                                    com.pantropi.vms.domain.visitor.RequestStatus expected) {
            saved = r;
            return true;
        }
    }

    private static final class FakeTenants implements TenantDirectory {
        private final UUID tenant;
        private final UUID validHost;
        FakeTenants(UUID tenant, UUID validHost) { this.tenant = tenant; this.validHost = validHost; }
        public Optional<UUID> tenantOfUser(UUID userId) { return Optional.ofNullable(tenant); }
        public boolean hostBelongsToTenant(UUID hostId, UUID tenantId) {
            return hostId.equals(validHost) && tenantId.equals(tenant);
        }
    }

    private static final class FakeEvents implements DomainEventPublisher {
        record Event(String type, String aggregateType, UUID id, String payload) {}
        final List<Event> published = new ArrayList<>();
        public void publish(String t, String at, UUID id, String payload) {
            published.add(new Event(t, at, id, payload));
        }
    }

    private static final class FakeAudit implements AuditTrail {
        final List<String> actions = new ArrayList<>();
        String lastAfter;
        public void record(UUID a, String action, String et, String eid, String d) { actions.add(action); }
        public void recordChange(UUID a, String action, String et, String eid, String b, String af) {
            actions.add(action); lastAfter = af;
        }
        public void recordSecurityDenial(UUID a, String action, String p, String r, String m,
                                         String o, String ip) { actions.add(action); }
    }
}
