package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A tenant submits a visitor entry request (US-07.1.1, T-07.1.1.3) — FR-VMS-01 (SRS B1).
 *
 * <p>Pure orchestration over ports: no HTTP type, no JPA type, no framework import.
 *
 * <p>Two security properties are structural rather than checked:
 * <ul>
 *   <li>{@code tenantId} is resolved from the <em>submitting user's own record</em> and is not a
 *       parameter of {@link Command}. A client cannot supply one, so it cannot be overridden
 *       (AC-5, OWASP A01).</li>
 *   <li>The published event is built from identifiers and the window only — it cannot carry visitor
 *       personal data, because the payload is assembled here and never from the visitor objects
 *       (AC-2).</li>
 * </ul>
 *
 * <p>Persistence, the audit entry and the event all happen inside one transaction, so a request is
 * never recorded without its event, nor an event emitted for a write that rolled back (AC-3).
 */
public final class SubmitVisitorRequest {

    private final VisitorRequestRepository requests;
    private final TenantDirectory tenants;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final VisitorTypeDirectory visitorTypes;

    public SubmitVisitorRequest(VisitorRequestRepository requests, TenantDirectory tenants,
                                DomainEventPublisher events, AuditTrail audit, TransactionRunner tx,
                                VisitorTypeDirectory visitorTypes) {
        this.requests = requests;
        this.tenants = tenants;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.visitorTypes = visitorTypes;
    }

    /**
     * @param requestedBy the authenticated submitter; the tenant is derived from them
     * @return the id of the created request
     */
    public UUID submit(UUID requestedBy, Command command) {
        UUID tenantId = tenants.tenantOfUser(requestedBy)
                .orElseThrow(() -> new NoTenantForUser(requestedBy));

        if (command.hostId() != null && !tenants.hostBelongsToTenant(command.hostId(), tenantId)) {
            throw new HostNotInTenant();
        }

        // Domain invariants — an invalid window cannot be constructed (AC-4).
        TimeWindow window = new TimeWindow(command.scheduledFrom(), command.scheduledTo());
        // AC-3: every visitor type is resolved before a single Visitor is built, so an unknown or
        // retired one refuses the whole submission rather than half of it. Nothing has been written
        // at this point either way — the transaction opens below.
        for (VisitorDetail detail : command.visitors()) {
            if (detail.visitorTypeId() != null
                    && !visitorTypes.isSelectable(detail.visitorTypeId())) {
                throw new UnknownVisitorType();
            }
        }

        List<Visitor> visitors = command.visitors().stream()
                .map(v -> Visitor.named(v.fullName(), v.email(), v.phone(), v.company(),
                        v.visitorTypeId()))
                .toList();

        VisitorRequest request = VisitorRequest.submit(
                tenantId, command.hostId(), requestedBy, window, command.purpose(), visitors);

        return tx.call(() -> {
            requests.save(request);

            // AC-3: audit carries counts and identifiers, never visitor personal data.
            audit.recordChange(requestedBy, "visitor_request.submit", "visitor_request",
                    request.id().toString(), null,
                    "{\"tenantId\":\"" + request.tenantId() + "\","
                            + "\"hostId\":" + quoted(request.hostId()) + ","
                            + "\"status\":\"" + request.status().dbValue() + "\","
                            + "\"visitKind\":\"" + request.visitKind().dbValue() + "\","
                            + "\"visitorCount\":" + request.visitors().size() + ","
                            + "\"scheduledFrom\":\"" + window.from() + "\","
                            + "\"scheduledTo\":\"" + window.to() + "\"}");

            // AC-2: identifiers and timing only — no name, email or phone.
            events.publish("VisitorRequestSubmitted", "visitor_request", request.id(),
                    "{\"requestId\":\"" + request.id() + "\","
                            + "\"tenantId\":\"" + request.tenantId() + "\","
                            + "\"hostId\":" + quoted(request.hostId()) + ","
                            + "\"scheduledFrom\":\"" + window.from() + "\","
                            + "\"scheduledTo\":\"" + window.to() + "\","
                            + "\"visitorCount\":" + request.visitors().size() + "}");

            return request.id();
        });
    }

    private static String quoted(UUID value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    /**
     * Submission input. Deliberately carries no {@code tenantId}, {@code status},
     * {@code requestedBy} or {@code approvedBy}: those are server-determined, so they cannot be
     * mass-assigned from a request body (AC-5).
     */
    public record Command(UUID hostId, Instant scheduledFrom, Instant scheduledTo, String purpose,
                          List<VisitorDetail> visitors) {}

    /**
     * @param visitorTypeId optional classification from {@code vms.visitor_types} (US-07.1.2 AC-1);
     *                      an unknown or inactive one refuses the submission
     */
    public record VisitorDetail(String fullName, String email, String phone, String company,
                                UUID visitorTypeId) {}

    // ---- failures ----

    public static final class NoTenantForUser extends IllegalStateException {
        public NoTenantForUser(UUID userId) {
            super("The submitting user " + userId + " is not linked to a tenant");
        }
    }

    /**
     * Names the field, not the value (US-07.1.2 AC-3).
     *
     * <p>Unknown and inactive are one failure deliberately: telling a tenant which of the two it was
     * would confirm that some id they guessed corresponds to a real type.
     */
    public static final class UnknownVisitorType extends IllegalArgumentException {
        public UnknownVisitorType() {
            super("The visitor type is not a known active type");
        }
    }

    public static final class HostNotInTenant extends IllegalArgumentException {
        public HostNotInTenant() {
            super("The host must be an active host of your tenant");
        }
    }
}
