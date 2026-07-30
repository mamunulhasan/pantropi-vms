package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.AuditProjection;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.ReceptionScope;
import com.pantropi.vms.application.visitor.port.RegistrationPolicy;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * A floor receptionist enters a visitor before they arrive (US-08.1.1, T-08.1.1.1) —
 * FR-VMS-03 (SRS B1).
 *
 * <p>The second way a visitor enters the system. It produces the same {@link VisitorRequest}
 * aggregate as a tenant's own submission, which T-08.1.1.1 asks for explicitly: a parallel model for
 * pre-registrations would need its own state machine, its own approval path and its own cascade
 * rules, and the two would drift apart at the first change to either.
 *
 * <h2>The tenant is derived, and AC-2 cannot always derive it</h2>
 * AC-2 describes the chain {@code reception_id → floor_id → tenant} as identifying one tenant. The
 * schema disagrees: {@code vms.tenants.floor_id} is a nullable foreign key with a non-unique index,
 * so a floor hosts as many tenants as it hosts — normally more than one in a commercial tower.
 *
 * <p>So:
 * <ul>
 *   <li><strong>One tenant on the floor</strong> — derived, exactly as AC-2 describes.</li>
 *   <li><strong>Several</strong> — the caller must say which, and it is checked against the floor
 *       they are actually stationed on. A tenant off that floor is a {@link ForeignFloor}, which the
 *       endpoint answers 403 to and audits (AC-6).</li>
 *   <li><strong>None</strong> — {@link NoTenantOnFloor}. Nothing to file the visit against.</li>
 * </ul>
 *
 * <p>This deviates from AC-2's literal wording, which says the tenant is "never from the submitted
 * payload". It preserves what that wording is protecting — the tenant is server-authoritative and a
 * receptionist cannot reach past their own floor — while leaving the feature usable on a
 * multi-tenant floor. Recorded as TODO-20 rather than settled here; if the answer is that a
 * receptionist should not choose at all, this is the one method that changes.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class PreRegisterVisitor {

    private final VisitorRequestRepository requests;
    private final ReceptionScope receptions;
    private final TenantDirectory tenants;
    private final VisitorTypeDirectory visitorTypes;
    private final RegistrationPolicy policy;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;

    public PreRegisterVisitor(VisitorRequestRepository requests, ReceptionScope receptions,
                              TenantDirectory tenants, VisitorTypeDirectory visitorTypes,
                              RegistrationPolicy policy, DomainEventPublisher events,
                              AuditTrail audit, TransactionRunner tx, ClockPort clock) {
        this.requests = requests;
        this.receptions = receptions;
        this.tenants = tenants;
        this.visitorTypes = visitorTypes;
        this.policy = policy;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param receptionist the authenticated floor receptionist, from the token
     * @return the created request and visitor ids
     * @throws NotStationed        if the user has no active reception (AC-2, T-08.1.1.2)
     * @throws NoTenantOnFloor     if their floor hosts no active tenant
     * @throws TenantNotSpecified  if the floor hosts several and none was named
     * @throws ForeignFloor        if the named tenant is not on their floor (AC-6)
     * @throws AppointmentInPast   if the window starts before the configured grace (AC-5)
     */
    public Registered preRegister(UUID receptionist, Command command) {
        ReceptionScope.Station station = receptions.stationOf(receptionist)
                .orElseThrow(() -> new NotStationed(receptionist));

        UUID tenantId = resolveTenant(station, command.tenantId());

        if (command.hostId() != null && !tenants.hostBelongsToTenant(command.hostId(), tenantId)) {
            throw new SubmitVisitorRequest.HostNotInTenant();
        }
        if (command.visitorTypeId() != null
                && !visitorTypes.isSelectable(command.visitorTypeId())) {
            throw new SubmitVisitorRequest.UnknownVisitorType();
        }

        // AC-1: the appointment window is the request window. vms.visitors.appointment_from/to are
        // written from it when the aggregate is saved, so the two cannot disagree.
        TimeWindow window = new TimeWindow(command.appointmentFrom(), command.appointmentTo());
        requireNotStale(window);

        VisitorRequest request = VisitorRequest.submit(tenantId, command.hostId(), receptionist,
                window, command.purpose(),
                List.of(Visitor.named(command.fullName(), command.email(), command.phone(),
                        command.company(), command.visitorTypeId())));

        UUID visitorId = request.visitors().get(0).id();

        return tx.call(() -> {
            requests.save(request);

            // AC-3: who registered it, from which reception point, and for whom — by id. The
            // projection decides what may appear; the reception id is added because "which desk did
            // this come from" is the question an incident review asks first.
            audit.record(receptionist, "visitor.pre_register", "visitor", visitorId.toString(),
                    null);
            audit.recordChange(receptionist, "visitor_request.pre_register", "visitor_request",
                    request.id().toString(), null,
                    AuditProjection.start()
                            .put("status", request.status().dbValue())
                            .put("receptionId", station.receptionId())
                            .put("tenantId", tenantId)
                            .put("visitorId", visitorId)
                            .put("appointmentFrom", window.from())
                            .put("appointmentTo", window.to())
                            .put("tenantDerived", command.tenantId() == null)
                            .json());

            // AC-3: ids only — no name, email or phone.
            events.publish("VisitorPreRegistered", "visitor_request", request.id(),
                    "{\"requestId\":\"" + request.id() + "\","
                            + "\"visitorId\":\"" + visitorId + "\","
                            + "\"tenantId\":\"" + tenantId + "\","
                            + "\"receptionId\":\"" + station.receptionId() + "\","
                            + "\"registeredBy\":\"" + receptionist + "\","
                            + "\"appointmentFrom\":\"" + window.from() + "\","
                            + "\"appointmentTo\":\"" + window.to() + "\"}");

            return new Registered(request.id(), visitorId, tenantId, station.receptionId());
        });
    }

    /**
     * AC-2 and AC-6 in one place: the tenant is the floor's, and the floor is the receptionist's.
     *
     * <p>A named tenant is a <em>selection from</em> what the station allows, never an assertion the
     * server accepts. That is the distinction that keeps the deviation from AC-2's wording safe.
     */
    private static UUID resolveTenant(ReceptionScope.Station station, UUID named) {
        if (station.tenantsOnFloor().isEmpty()) {
            throw new NoTenantOnFloor(station.floorId());
        }
        if (named == null) {
            if (station.identifiesOneTenant()) {
                return station.tenantsOnFloor().get(0);
            }
            throw new TenantNotSpecified(station.tenantsOnFloor().size());
        }
        if (!station.hosts(named)) {
            // AC-6: reaching past their own floor. 403 and audited, not 404 — unlike a request id,
            // a tenant is not a secret, and refusing plainly tells an honest caller what is wrong.
            throw new ForeignFloor();
        }
        return named;
    }

    /**
     * AC-5: an appointment that started too long ago is a mistyped date.
     *
     * <p>The window's own invariant already refuses {@code to <= from} ({@link TimeWindow}), so this
     * only has to judge staleness. The grace comes from configuration because the right answer is
     * operational, not architectural — a lobby that pre-registers a day ahead and one that types
     * people in as they walk up want different numbers.
     */
    private void requireNotStale(TimeWindow window) {
        int graceMinutes = policy.pastAppointmentGraceMinutes();
        Instant earliest = clock.now().minus(graceMinutes, ChronoUnit.MINUTES);
        if (window.from().isBefore(earliest)) {
            throw new AppointmentInPast(graceMinutes);
        }
    }

    /**
     * Pre-registration input.
     *
     * <p>Carries no {@code status}, {@code approvedBy}, {@code floorId} or {@code receptionId} — all
     * server-determined. {@code tenantId} is present but is a selection within the caller's own
     * floor, checked above; it cannot widen what they may do.
     */
    public record Command(String fullName, String email, String phone, String company,
                          UUID visitorTypeId, UUID hostId, UUID tenantId, String purpose,
                          Instant appointmentFrom, Instant appointmentTo) {}

    public record Registered(UUID requestId, UUID visitorId, UUID tenantId, UUID receptionId) {}

    // ---- failures ----

    /** No active reception, so no floor, so nothing to derive a tenant from (T-08.1.1.2). */
    public static final class NotStationed extends IllegalStateException {
        public NotStationed(UUID userId) {
            super("User " + userId + " is not stationed at an active reception");
        }
    }

    public static final class NoTenantOnFloor extends IllegalStateException {
        public NoTenantOnFloor(UUID floorId) {
            super("Floor " + floorId + " has no active tenant to register a visit against");
        }
    }

    /** Several tenants share the floor, so AC-2's chain does not identify one (TODO-20). */
    public static final class TenantNotSpecified extends IllegalArgumentException {
        public final int candidates;

        public TenantNotSpecified(int candidates) {
            super("This floor hosts " + candidates + " tenants; say which the visit is for");
            this.candidates = candidates;
        }
    }

    /** AC-6: a tenant outside the receptionist's own floor. */
    public static final class ForeignFloor extends SecurityException {
        public ForeignFloor() {
            super("That tenant is not on your floor");
        }
    }

    public static final class AppointmentInPast extends IllegalArgumentException {
        public AppointmentInPast(int graceMinutes) {
            super("The appointment starts more than " + graceMinutes
                    + " minutes in the past; check the date");
        }
    }
}
