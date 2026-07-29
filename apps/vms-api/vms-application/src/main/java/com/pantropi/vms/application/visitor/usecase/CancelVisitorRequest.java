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
 * A tenant withdraws its own request (US-07.1.3, T-07.1.3.1/2) — FR-VMS-01 (SRS B1).
 *
 * <h2>Cancelling an approved request is allowed, and that is the interesting case</h2>
 * AC-3 permits it, and the reason is practical: a plan changes after approval more often than
 * before it, and the alternative is a visitor arriving whom nobody expects to turn away. What it
 * costs is a credential that may already exist, so a cancellation that withdrew an approval emits a
 * second event — {@code CredentialRevocationRequested} — for F-09.7 to consume.
 *
 * <p>That is a separate event rather than a flag on the first one on purpose. A revocation consumer
 * subscribing to {@code VisitorRequestCancelled} would have to filter, and a consumer that forgot to
 * filter would try to revoke a credential that was never issued. The event that means "revoke
 * something" is only published when there is something to revoke.
 *
 * <h2>Racing an approval (AC-5)</h2>
 * The same compare-and-set as every other write on this aggregate. A tenant cancelling from
 * {@code submitted} and an admin approving from {@code submitted} both expect that state; one lands
 * and the other gets 409. The request is therefore never both approved and cancelled, and the loser
 * contributes no event — which matters here, because a spurious revocation signal would revoke a
 * credential belonging to a visit that is still going ahead.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class CancelVisitorRequest {

    private final VisitorRequestRepository requests;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;

    public CancelVisitorRequest(VisitorRequestRepository requests, DomainEventPublisher events,
                                AuditTrail audit, TransactionRunner tx, ClockPort clock) {
        this.requests = requests;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param actor the authenticated tenant user, from the token
     * @throws RequestDecision.NotFound if absent or not the caller's to see (AC-6)
     * @throws com.pantropi.vms.domain.visitor.RequestTransitions.IllegalTransition
     *                                  from a state already terminal (AC-4)
     * @throws RequestDecision.DecidedElsewhere if a decision landed first (AC-5)
     */
    public RequestDecision cancel(UUID actor, UUID requestId) {
        VisitorRequest request = requests.findById(requestId)
                .orElseThrow(() -> new RequestDecision.NotFound(requestId));

        RequestStatus previous = request.status();
        Instant now = clock.now();
        request.cancel(now);

        boolean withdrewAnApproval = request.cancelledAfterApproval();

        return tx.call(() -> {
            if (!requests.saveDecision(request, previous)) {
                throw new RequestDecision.DecidedElsewhere(requestId);
            }

            // AC-3: the audit says what the cancellation undid, not merely that one happened. Months
            // later "was this visit ever approved?" is the question, and the request row itself no
            // longer answers it.
            audit.recordChange(actor, "visitor_request.cancel", "visitor_request",
                    requestId.toString(),
                    AuditProjection.before(previous),
                    AuditProjection.start()
                            .put("status", request.status().dbValue())
                            .put("cancelledBy", actor)
                            .put("cancelledAt", now)
                            .put("followedApproval", withdrewAnApproval)
                            .putRaw("visitorCount", String.valueOf(request.visitors().size()))
                            .json());

            events.publish("VisitorRequestCancelled", "visitor_request", requestId,
                    "{\"requestId\":\"" + requestId + "\","
                            + "\"tenantId\":\"" + request.tenantId() + "\","
                            + "\"cancelledBy\":\"" + actor + "\","
                            + "\"cancelledAt\":\"" + now + "\","
                            + "\"followedApproval\":" + withdrewAnApproval + ","
                            + "\"visitorIds\":" + jsonIds(request.visitorIds()) + "}");

            if (withdrewAnApproval) {
                // AC-3: only when an approval was withdrawn, so a consumer of this event never has
                // to ask whether there is anything to revoke.
                events.publish("CredentialRevocationRequested", "visitor_request", requestId,
                        "{\"requestId\":\"" + requestId + "\","
                                + "\"reason\":\"request_cancelled\","
                                + "\"requestedAt\":\"" + now + "\","
                                + "\"visitorIds\":" + jsonIds(request.visitorIds()) + "}");
            }

            return new RequestDecision(requestId, request.status().dbValue(), actor, now, null);
        });
    }

    private static String jsonIds(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
