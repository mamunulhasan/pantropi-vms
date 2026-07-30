package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.ReceptionScope;
import com.pantropi.vms.application.visitor.port.RegistrationPolicy;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.application.visitor.usecase.PreRegisterVisitor;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.VisitKind;
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
 * US-08.1.1 · T-08.1.1.1 — pre-registration over fake ports.
 *
 * <p>Most of these are about AC-2 and AC-6, which together decide which tenant a visit is filed
 * against. Getting that wrong attributes somebody's visitor to another company, and nothing later in
 * the pipeline would notice.
 */
class PreRegisterVisitorTest {

    private static final Instant NOW = Instant.parse("2030-06-01T09:00:00Z");
    private static final UUID RECEPTIONIST = UUID.randomUUID();
    private static final UUID RECEPTION = UUID.randomUUID();
    private static final UUID FLOOR = UUID.randomUUID();
    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID HOST = UUID.randomUUID();

    private final FakeRepo repo = new FakeRepo();
    private final FakeStations stations = new FakeStations();
    private final FakeTenants tenants = new FakeTenants();
    private final FakeTypes types = new FakeTypes();
    private final FakeEvents events = new FakeEvents();
    private final FakeAudit audit = new FakeAudit();
    private final CountingRunner tx = new CountingRunner();
    private int grace = 60;

    private PreRegisterVisitor useCase() {
        return new PreRegisterVisitor(repo, stations, tenants, types,
                (RegistrationPolicy) () -> grace, events, audit, tx, () -> NOW);
    }

    // ---- AC-1 ----

    @Test
    @DisplayName("AC-1: the aggregate is the same one a tenant submission produces, pre_scheduled, "
            + "with one pending visitor and the appointment window on it")
    void producesTheSameAggregate() {
        stations.on(FLOOR, List.of(TENANT_A));

        PreRegisterVisitor.Registered result = useCase().preRegister(RECEPTIONIST, command(null));

        VisitorRequest saved = repo.saved;
        assertThat(saved.status()).isEqualTo(RequestStatus.SUBMITTED);
        assertThat(saved.visitKind()).isEqualTo(VisitKind.PRE_SCHEDULED);
        assertThat(saved.tenantId()).isEqualTo(TENANT_A);
        assertThat(saved.requestedBy()).isEqualTo(RECEPTIONIST);
        // The request window IS the appointment window, so the visitor rows cannot disagree with it.
        assertThat(saved.window().from()).isEqualTo(NOW.plus(2, ChronoUnit.HOURS));
        assertThat(saved.visitors()).singleElement().satisfies(v -> {
            assertThat(v.status()).isEqualTo(VisitorStatus.PENDING);
            assertThat(v.fullName()).isEqualTo("Ada Lovelace");
            assertThat(v.emailValue()).isEqualTo("ada@example.test");
        });
        assertThat(result.receptionId()).isEqualTo(RECEPTION);
        assertThat(result.visitorId()).isEqualTo(saved.visitors().get(0).id());
    }

    // ---- AC-2: the tenant is the floor's ----

    @Test
    @DisplayName("AC-2: one tenant on the floor is derived, exactly as the AC describes")
    void singleTenantIsDerived() {
        stations.on(FLOOR, List.of(TENANT_A));

        useCase().preRegister(RECEPTIONIST, command(null));

        assertThat(repo.saved.tenantId()).isEqualTo(TENANT_A);
        // Recorded so a reader of the trail can tell a derived tenant from a chosen one.
        assertThat(audit.changes).anySatisfy(e -> assertThat(e).contains("\"tenantDerived\":true"));
    }

    @Test
    @DisplayName("AC-2/TODO-20: several tenants share the floor, so one must be named")
    void severalTenantsRequireAChoice() {
        stations.on(FLOOR, List.of(TENANT_A, TENANT_B));

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST, command(null)))
                .isInstanceOfSatisfying(PreRegisterVisitor.TenantNotSpecified.class,
                        e -> assertThat(e.candidates).isEqualTo(2));

        assertThat(repo.saved).isNull();
    }

    @Test
    @DisplayName("AC-2: a named tenant on my own floor is accepted and recorded as chosen")
    void namedTenantOnMyFloorIsAccepted() {
        stations.on(FLOOR, List.of(TENANT_A, TENANT_B));

        useCase().preRegister(RECEPTIONIST, command(TENANT_B));

        assertThat(repo.saved.tenantId()).isEqualTo(TENANT_B);
        assertThat(audit.changes).anySatisfy(e -> assertThat(e).contains("\"tenantDerived\":false"));
    }

    @Test
    @DisplayName("AC-6: a tenant not on my floor is refused, and nothing is written")
    void foreignTenantIsRefused() {
        stations.on(FLOOR, List.of(TENANT_A));

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST, command(TENANT_B)))
                .isInstanceOf(PreRegisterVisitor.ForeignFloor.class);

        assertThat(repo.saved).isNull();
        assertThat(events.published).isEmpty();
        assertThat(tx.calls).isZero();
    }

    @Test
    @DisplayName("AC-6: naming my own floor's tenant is a selection, never an assertion — the "
            + "command cannot carry a floor or a reception at all")
    void commandCannotAssertAStation() {
        assertThat(PreRegisterVisitor.Command.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("floorId", "receptionId", "status", "approvedBy", "requestedBy");
    }

    @Test
    @DisplayName("AC-2/T-08.1.1.2: a user with no active reception gets a clear error, not a null "
            + "threaded onwards")
    void unstationedUserIsRefused() {
        stations.none();

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST, command(null)))
                .isInstanceOf(PreRegisterVisitor.NotStationed.class);

        assertThat(repo.saved).isNull();
    }

    @Test
    @DisplayName("a floor with no active tenant has nothing to file a visit against")
    void emptyFloorIsRefused() {
        stations.on(FLOOR, List.of());

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST, command(null)))
                .isInstanceOf(PreRegisterVisitor.NoTenantOnFloor.class);
    }

    // ---- AC-5: the window ----

    @Test
    @DisplayName("AC-5: an appointment inside the grace period is accepted")
    void recentAppointmentIsAccepted() {
        // The normal case: a receptionist typing somebody in as they arrive. The rule exists to
        // catch a mistyped date, not to insist the paperwork precede the visitor.
        stations.on(FLOOR, List.of(TENANT_A));

        useCase().preRegister(RECEPTIONIST, commandAt(NOW.minus(30, ChronoUnit.MINUTES)));

        assertThat(repo.saved).isNotNull();
    }

    @Test
    @DisplayName("AC-5: an appointment older than the grace period is refused, and nothing persists")
    void staleAppointmentIsRefused() {
        stations.on(FLOOR, List.of(TENANT_A));

        assertThatThrownBy(() ->
                useCase().preRegister(RECEPTIONIST, commandAt(NOW.minus(61, ChronoUnit.MINUTES))))
                .isInstanceOf(PreRegisterVisitor.AppointmentInPast.class);

        assertThat(repo.saved).isNull();
        assertThat(tx.calls).isZero();
    }

    @Test
    @DisplayName("AC-5: the grace is configuration — widening it accepts what was refused")
    void graceIsConfigured() {
        stations.on(FLOOR, List.of(TENANT_A));
        grace = 24 * 60;

        useCase().preRegister(RECEPTIONIST, commandAt(NOW.minus(61, ChronoUnit.MINUTES)));

        assertThat(repo.saved).isNotNull();
    }

    @Test
    @DisplayName("AC-5: an inverted window is refused by the window's own invariant")
    void invertedWindowIsRefused() {
        stations.on(FLOOR, List.of(TENANT_A));
        Instant from = NOW.plus(2, ChronoUnit.HOURS);

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST,
                new PreRegisterVisitor.Command("Ada", null, null, null, null, null, null, null,
                        from, from.minus(1, ChronoUnit.HOURS))))
                .isInstanceOf(TimeWindow.InvalidTimeWindow.class);

        assertThat(repo.saved).isNull();
    }

    // ---- AC-3 ----

    @Test
    @DisplayName("AC-3: the audit names the reception point and the visitor by id, and the event "
            + "carries ids only")
    void auditAndEventCarryIdsOnly() {
        stations.on(FLOOR, List.of(TENANT_A));

        useCase().preRegister(RECEPTIONIST, command(null));

        assertThat(audit.simple).contains("visitor.pre_register");
        assertThat(audit.changes).anySatisfy(e -> assertThat(e)
                .contains(RECEPTION.toString()).contains(TENANT_A.toString())
                .doesNotContain("Ada Lovelace").doesNotContain("ada@example.test"));

        assertThat(events.published).hasSize(1);
        assertThat(events.published.get(0).type()).isEqualTo("VisitorPreRegistered");
        assertThat(events.published.get(0).payload())
                .contains(RECEPTION.toString()).contains(TENANT_A.toString())
                .doesNotContain("Ada Lovelace").doesNotContain("ada@example.test")
                .doesNotContain("880");
    }

    @Test
    @DisplayName("the save, the audit and the event all happen in one transaction")
    void oneTransaction() {
        stations.on(FLOOR, List.of(TENANT_A));

        useCase().preRegister(RECEPTIONIST, command(null));

        assertThat(tx.calls).isEqualTo(1);
    }

    // ---- reused validation ----

    @Test
    @DisplayName("a host outside the resolved tenant is refused, using the same rule as submission")
    void foreignHostIsRefused() {
        stations.on(FLOOR, List.of(TENANT_A));
        tenants.hostValid = false;

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST, command(null)))
                .isInstanceOf(SubmitVisitorRequest.HostNotInTenant.class);
        assertThat(repo.saved).isNull();
    }

    @Test
    @DisplayName("an unusable visitor type is refused, using the same rule as submission")
    void retiredTypeIsRefused() {
        stations.on(FLOOR, List.of(TENANT_A));
        UUID retired = UUID.randomUUID();
        types.retired.add(retired);

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST,
                new PreRegisterVisitor.Command("Ada", null, null, null, retired, null, null, null,
                        NOW.plus(2, ChronoUnit.HOURS), NOW.plus(4, ChronoUnit.HOURS))))
                .isInstanceOf(SubmitVisitorRequest.UnknownVisitorType.class);
        assertThat(repo.saved).isNull();
    }

    @Test
    @DisplayName("a malformed email is refused without being echoed")
    void malformedEmailIsRefused() {
        stations.on(FLOOR, List.of(TENANT_A));

        assertThatThrownBy(() -> useCase().preRegister(RECEPTIONIST,
                new PreRegisterVisitor.Command("Ada", "not-an-email", null, null, null, null, null,
                        null, NOW.plus(2, ChronoUnit.HOURS), NOW.plus(4, ChronoUnit.HOURS))))
                .isInstanceOf(Visitor.InvalidVisitorDetail.class)
                .hasMessageNotContaining("not-an-email");
    }

    // ---- helpers ----

    private PreRegisterVisitor.Command command(UUID tenantId) {
        return commandAt(NOW.plus(2, ChronoUnit.HOURS), tenantId);
    }

    private PreRegisterVisitor.Command commandAt(Instant from) {
        return commandAt(from, null);
    }

    private PreRegisterVisitor.Command commandAt(Instant from, UUID tenantId) {
        return new PreRegisterVisitor.Command("Ada Lovelace", "ada@example.test", "+8801712345678",
                "Analytical Ltd", null, HOST, tenantId, "Site inspection",
                from, from.plus(2, ChronoUnit.HOURS));
    }

    private static final class FakeStations implements ReceptionScope {
        private Station station;

        void on(UUID floorId, List<UUID> tenantsOnFloor) {
            station = new Station(RECEPTION, floorId, tenantsOnFloor);
        }

        void none() {
            station = null;
        }

        public Optional<Station> stationOf(UUID userId) {
            return Optional.ofNullable(station);
        }
    }

    /**
     * The host is valid for whichever tenant was resolved, unless a test says otherwise.
     *
     * <p>Tied to a single tenant, this fake made every tenant-selection test trip the host check
     * instead of the rule it was written for.
     */
    private static final class FakeTenants implements TenantDirectory {
        boolean hostValid = true;

        public Optional<UUID> tenantOfUser(UUID userId) {
            return Optional.empty();     // a receptionist has no tenant of their own
        }

        public boolean hostBelongsToTenant(UUID hostId, UUID tenantId) {
            return hostValid;
        }
    }

    private static final class FakeTypes implements VisitorTypeDirectory {
        final java.util.Set<UUID> retired = new java.util.HashSet<>();

        public boolean isSelectable(UUID id) {
            return id != null && !retired.contains(id);
        }
    }

    private static final class FakeRepo implements VisitorRequestRepository {
        VisitorRequest saved;

        public void save(VisitorRequest r) {
            saved = r;
        }

        public Optional<VisitorRequest> findById(UUID id) {
            return Optional.ofNullable(saved);
        }

        public boolean saveDecision(VisitorRequest r, RequestStatus expected) {
            return true;
        }

        public boolean saveAmendment(VisitorRequest r, RequestStatus expected) {
            return true;
        }
    }

    private static final class FakeEvents implements DomainEventPublisher {
        record Event(String type, String payload) {}

        final List<Event> published = new ArrayList<>();

        public void publish(String type, String aggregateType, UUID id, String payload) {
            published.add(new Event(type, payload));
        }
    }

    private static final class FakeAudit implements AuditTrail {
        final List<String> simple = new ArrayList<>();
        final List<String> changes = new ArrayList<>();

        public void record(UUID actor, String action, String entityType, String entityId,
                           String detail) {
            simple.add(action);
        }

        public void recordChange(UUID actor, String action, String entityType, String entityId,
                                 String before, String after) {
            changes.add(after);
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
