package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.visitor.AuditProjection;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A tenant amends its own request while it is still awaiting a decision (US-07.1.3, T-07.1.3.1) —
 * FR-VMS-01 (SRS B1).
 *
 * <p>Every rule that governs a submission governs an amendment, and for the same reasons: the host
 * must belong to the caller's tenant, visitor types must be selectable, and personal details are
 * validated and normalised by their value objects. The checks are reused rather than restated —
 * an amendment path with its own, slightly different validation is how a rule ends up enforced on
 * the way in and not on the way back in.
 *
 * <p>The tenant is never amendable, and there is no field for it. A request belongs to the tenant
 * that raised it; moving it elsewhere is not an edit.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class AmendVisitorRequest {

    private final VisitorRequestRepository requests;
    private final TenantDirectory tenants;
    private final VisitorTypeDirectory visitorTypes;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;

    public AmendVisitorRequest(VisitorRequestRepository requests, TenantDirectory tenants,
                               VisitorTypeDirectory visitorTypes, DomainEventPublisher events,
                               AuditTrail audit, TransactionRunner tx, ClockPort clock) {
        this.requests = requests;
        this.tenants = tenants;
        this.visitorTypes = visitorTypes;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @throws RequestDecision.NotFound if absent or not the caller's to see (AC-6)
     * @throws VisitorRequest.RequestNotPending once a decision has been taken (AC-4)
     * @throws RequestDecision.DecidedElsewhere if a decision landed while this ran (AC-5)
     */
    public RequestDecision amend(UUID actor, UUID requestId, Amendment amendment) {
        VisitorRequest request = requests.findById(requestId)
                .orElseThrow(() -> new RequestDecision.NotFound(requestId));

        RequestStatus current = request.status();

        if (amendment.hostId() != null
                && !tenants.hostBelongsToTenant(amendment.hostId(), request.tenantId())) {
            throw new SubmitVisitorRequest.HostNotInTenant();
        }

        List<Visitor> visitors = null;
        if (amendment.visitors() != null) {
            for (SubmitVisitorRequest.VisitorDetail detail : amendment.visitors()) {
                if (detail.visitorTypeId() != null
                        && !visitorTypes.isSelectable(detail.visitorTypeId())) {
                    throw new SubmitVisitorRequest.UnknownVisitorType();
                }
            }
            visitors = amendment.visitors().stream()
                    .map(v -> Visitor.named(v.fullName(), v.email(), v.phone(), v.company(),
                            v.visitorTypeId()))
                    .toList();
        }

        TimeWindow window = amendment.scheduledFrom() == null && amendment.scheduledTo() == null
                ? null
                : new TimeWindow(
                        amendment.scheduledFrom() == null
                                ? request.window().from() : amendment.scheduledFrom(),
                        amendment.scheduledTo() == null
                                ? request.window().to() : amendment.scheduledTo());

        // Everything validated; the aggregate applies it or refuses it whole.
        request.amend(amendment.hostId(), window, amendment.purpose(), visitors);

        Instant now = clock.now();
        return tx.call(() -> {
            if (!requests.saveAmendment(request, current)) {
                throw new RequestDecision.DecidedElsewhere(requestId);
            }

            // AC-1: which fields changed and how many visitors there now are — never a name, an
            // email or a phone number.
            audit.recordChange(actor, "visitor_request.amend", "visitor_request",
                    requestId.toString(),
                    AuditProjection.before(current),
                    AuditProjection.start()
                            .put("status", request.status().dbValue())
                            .put("amendedBy", actor)
                            .put("amendedAt", now)
                            .put("hostChanged", amendment.hostId() != null)
                            .put("windowChanged", window != null)
                            .put("purposeChanged", amendment.purpose() != null)
                            .put("visitorsReplaced", amendment.visitors() != null)
                            .putRaw("visitorCount", String.valueOf(request.visitors().size()))
                            .json());

            events.publish("VisitorRequestAmended", "visitor_request", requestId,
                    "{\"requestId\":\"" + requestId + "\","
                            + "\"tenantId\":\"" + request.tenantId() + "\","
                            + "\"amendedBy\":\"" + actor + "\","
                            + "\"amendedAt\":\"" + now + "\","
                            + "\"scheduledFrom\":\"" + request.window().from() + "\","
                            + "\"scheduledTo\":\"" + request.window().to() + "\","
                            + "\"visitorCount\":" + request.visitors().size() + "}");

            return new RequestDecision(requestId, request.status().dbValue(), actor, now, null);
        });
    }

    /**
     * A partial edit: null means "leave this alone".
     *
     * <p>Carries no {@code tenantId}, {@code status}, {@code approvedBy} or {@code decisionReason} —
     * the same absence that protects the submission command (US-07.1.1 AC-5). A client cannot amend
     * its way to an approval.
     *
     * <p>Supplying only one end of the window keeps the other, so "move it an hour later" does not
     * require restating the start.
     */
    public record Amendment(UUID hostId, Instant scheduledFrom, Instant scheduledTo, String purpose,
                            List<SubmitVisitorRequest.VisitorDetail> visitors) {}
}
