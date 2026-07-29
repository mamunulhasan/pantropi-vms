package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.visitor.AuditProjection;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * FM Admin approval of a visitor request (US-07.4.1, T-07.4.1.2) — FR-VMS-02 (SRS B1).
 *
 * <h2>Exactly one approval, exactly one event (AC-6)</h2>
 * Two FM Admins can open the same request and both press approve. Each loads its own copy of the
 * aggregate, so each passes the aggregate's transition guard — the guard protects one instance, not
 * a row shared by two transactions. The database is where they meet, so the decision is written as a
 * compare-and-set against the state the caller decided from; the second one matches no row.
 *
 * <p>The loser then throws, which rolls back its audit entry and its outbox row along with the
 * update it never made. That is why the event is written to the outbox <em>inside</em> the same
 * transaction rather than published after commit: publication and state change succeed or fail
 * together, so a credential can never be ordered for a request this caller did not actually approve,
 * and a crash between the two cannot lose an event that a committed approval promised.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class ApproveVisitorRequest {

    private final VisitorRequestRepository requests;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;

    public ApproveVisitorRequest(VisitorRequestRepository requests, DomainEventPublisher events,
                                 AuditTrail audit, TransactionRunner tx, ClockPort clock) {
        this.requests = requests;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param approver  the authenticated FM Admin, taken from the token by the caller
     * @param requestId which request to approve
     * @param note      optional explanation, stored with the decision and visible to the tenant
     *                  (AC-4)
     * @return the decided state, for the response body
     * @throws RequestDecision.NotFound                           if it does not exist, or is not
     *                                                            the caller's to see
     * @throws com.pantropi.vms.domain.visitor.RequestTransitions.IllegalTransition
     *                                                            if already decided (AC-5)
     * @throws VisitorRequest.WindowAlreadyElapsed                if the visit is over (AC-7)
     * @throws RequestDecision.DecidedElsewhere                   if another admin got there first
     *                                                            (AC-6)
     */
    public RequestDecision approve(UUID approver, UUID requestId, String note) {
        VisitorRequest request = requests.findById(requestId)
                .orElseThrow(() -> new RequestDecision.NotFound(requestId));

        // Captured before the transition, so the audit trail can show what actually changed (AC-3).
        RequestStatus previous = request.status();

        // The aggregate decides whether this is legal; a refusal here reaches the caller untouched
        // and nothing has been written.
        Instant now = clock.now();
        request.approve(approver, note, now);

        return tx.call(() -> {
            if (!requests.saveDecision(request, previous)) {
                // Someone decided it between our read and our write. Throwing rolls back the audit
                // and outbox writes below, which have not happened yet — but the transaction is the
                // guarantee, not the ordering of these lines.
                throw new RequestDecision.DecidedElsewhere(requestId);
            }

            // AC-3: the allow-listed projection (US-07.4.3, T-07.4.3.1) — status, window,
            // decision and a visitor count, never a visitor. What it may contain is decided in one
            // place rather than restated at each of the four decision paths.
            audit.recordChange(approver, "visitor_request.approve", "visitor_request",
                    requestId.toString(),
                    AuditProjection.before(previous), AuditProjection.of(request));

            // AC-2: ids and the approved window only, for EPIC-09 credential orchestration. A
            // consumer that needs a visitor's name asks the API for it under its own authorisation.
            events.publish("VisitorRequestApproved", "visitor_request", requestId,
                    "{\"requestId\":\"" + requestId + "\","
                            + "\"tenantId\":\"" + request.tenantId() + "\","
                            + "\"approvedBy\":\"" + approver + "\","
                            + "\"approvedAt\":\"" + now + "\","
                            + "\"visitorIds\":" + jsonIds(request.visitorIds()) + ","
                            + "\"scheduledFrom\":\"" + request.window().from() + "\","
                            + "\"scheduledTo\":\"" + request.window().to() + "\"}");

            return new RequestDecision(requestId, request.status().dbValue(), approver, now,
                    request.decisionReason());
        });
    }

    private static String jsonIds(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

}
