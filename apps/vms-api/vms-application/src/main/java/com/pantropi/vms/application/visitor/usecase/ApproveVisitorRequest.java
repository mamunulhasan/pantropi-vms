package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.visitor.AuditProjection;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.CredentialIssuance;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.IssuancePolicy;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.time.Instant;
import java.util.ArrayList;
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
 * <h2>The passes are minted after the commit (US-09.1.2)</h2>
 * Approving is what mints the pass — nobody presses a second button. The issuance calls happen
 * <em>after</em> {@code tx.call} returns, and the placement is the design rather than an accident of
 * ordering. AC-4 says the approval is never rolled back because the access control system was
 * unreachable: approval and issuance are separate aggregates with eventual consistency between them.
 * Inside the transaction, a failing ACS call would undo a decision an FM legitimately took. After
 * the commit, it cannot — {@code SpringTransactionRunner} has already committed by the time the
 * method returns, and nothing thrown afterwards can reach back.
 *
 * <p>One call per visitor, each outcome captured on its own (AC-5): three passes issued and two
 * awaiting retry is a real and acceptable result, and reporting it per visitor is the only way the
 * approver learns which two.
 *
 * <p><strong>Deviation, recorded.</strong> The backlog specifies an idempotent Kafka consumer of
 * {@code VisitorRequestApproved}. No relay exists — the outbox is written and never drained — so the
 * call is made directly and the event is still published for the consumer that will one day read it.
 * AC-3's {@code event_id} idempotency therefore has nothing to guard: there is no second delivery.
 * Duplicate protection comes instead from the credential context's own live-credential check.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class ApproveVisitorRequest {

    private final VisitorRequestRepository requests;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;
    private final CredentialIssuance issuance;
    private final IssuancePolicy issuancePolicy;

    public ApproveVisitorRequest(VisitorRequestRepository requests, DomainEventPublisher events,
                                 AuditTrail audit, TransactionRunner tx, ClockPort clock,
                                 CredentialIssuance issuance, IssuancePolicy issuancePolicy) {
        this.requests = requests;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
        this.issuance = issuance;
        this.issuancePolicy = issuancePolicy;
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

        RequestDecision decided = tx.call(() -> {
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

        // Past this line the approval is durable. Nothing below may throw.
        return withPasses(approver, request, decided);
    }

    /**
     * Mints a pass for each visitor and folds the outcomes into the decision (US-09.1.2 AC-1/AC-5).
     *
     * <p>The {@code catch} is belt and braces. {@link CredentialIssuance} promises not to throw, but
     * this runs after a committed approval, and an adapter that breaks that promise must not be able
     * to turn a decision the FM successfully took into an error they cannot act on.
     */
    private RequestDecision withPasses(UUID approver, VisitorRequest request,
                                       RequestDecision decided) {
        if (!issuancePolicy.autoIssueOnApproval()) {
            // AC-2: switched off means issuance stays manual, not that approval fails.
            return decided;
        }

        List<RequestDecision.IssuedPass> issued = new ArrayList<>();
        for (UUID visitorId : request.visitorIds()) {
            CredentialIssuance.Outcome outcome;
            try {
                outcome = issuance.issueOnApproval(approver, visitorId, request.window().from(),
                        request.window().to());
            } catch (RuntimeException e) {
                outcome = CredentialIssuance.Outcome.FAILED;
            }
            issued.add(new RequestDecision.IssuedPass(visitorId, outcome.name()));
        }

        return new RequestDecision(decided.requestId(), decided.status(), decided.decidedBy(),
                decided.decidedAt(), decided.note(), issued);
    }

    private static String jsonIds(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

}
