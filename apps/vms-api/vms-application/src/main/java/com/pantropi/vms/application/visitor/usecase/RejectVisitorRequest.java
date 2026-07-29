package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.JsonText;
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
 * FM Admin rejection of a visitor request (US-07.4.2, T-07.4.2.2) — FR-VMS-02 (SRS B1).
 *
 * <p>Mirrors {@link ApproveVisitorRequest}: the same compare-and-set on the status, the same
 * single-event guarantee, the same transaction. Two differences are deliberate.
 *
 * <h2>The reason goes into the audit record</h2>
 * An approval note does not (US-07.4.1) — but AC-3 asks for the reason here, and the story says why:
 * a refusal has to be <em>defensible after the fact</em>. Months later the question is not that a
 * request was refused but on what grounds, and the audit trail is the only immutable place that can
 * answer it. That makes operator free text part of a {@code jsonb} payload built by concatenation,
 * which is why it goes through {@link JsonText} rather than being pasted in.
 *
 * <h2>The reason stays out of the event</h2>
 * The event exists so other contexts can react to the decision; none of them needs the prose, and an
 * event is the one artefact here that leaves the database for a message bus. Ids and timing only,
 * as everywhere else in this codebase.
 *
 * <p>AC-4 — rejection is a terminal branch. Nothing on this path emits anything EPIC-09 consumes, so
 * no ACS credential is ever created for a refused request.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class RejectVisitorRequest {

    private final VisitorRequestRepository requests;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;

    public RejectVisitorRequest(VisitorRequestRepository requests, DomainEventPublisher events,
                                AuditTrail audit, TransactionRunner tx, ClockPort clock) {
        this.requests = requests;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @param reason mandatory (AC-2); blank, whitespace-only and absent are all refused by the
     *               aggregate, so no caller — this one, a future bulk operation, an event handler —
     *               can record a refusal nobody has to justify
     * @throws RequestDecision.NotFound if absent or out of the caller's scope
     * @throws VisitorRequest.RejectionReasonRequired if no reason was given (AC-2)
     * @throws VisitorRequest.DecisionReasonTooLong  if it exceeds the bound
     * @throws com.pantropi.vms.domain.visitor.RequestTransitions.IllegalTransition
     *                                               from any state but {@code SUBMITTED} (AC-5)
     * @throws RequestDecision.DecidedElsewhere if another admin decided it first
     */
    public RequestDecision reject(UUID approver, UUID requestId, String reason) {
        VisitorRequest request = requests.findById(requestId)
                .orElseThrow(() -> new RequestDecision.NotFound(requestId));

        RequestStatus previous = request.status();

        // No elapsed-window rule here, unlike approval. Approving a finished visit would mint an
        // already-expired credential; refusing one is harmless and is sometimes exactly what the
        // record should say.
        Instant now = clock.now();
        request.reject(approver, reason, now);

        return tx.call(() -> {
            if (!requests.saveDecision(request, previous)) {
                throw new RequestDecision.DecidedElsewhere(requestId);
            }

            // AC-3: who, when, and — unlike an approval — why. Escaped, because this is the one
            // audit payload in the system that carries text a person typed.
            audit.recordChange(approver, "visitor_request.reject", "visitor_request",
                    requestId.toString(),
                    "{\"status\":\"" + previous.dbValue() + "\"}",
                    "{\"status\":\"" + request.status().dbValue() + "\","
                            + "\"decidedBy\":\"" + approver + "\","
                            + "\"decidedAt\":\"" + now + "\","
                            + "\"reason\":" + JsonText.quoted(request.decisionReason()) + ","
                            + "\"visitorCount\":" + request.visitors().size() + "}");

            // AC-3/AC-4: the decision, for anything that tracks request lifecycle. Nothing here is
            // consumed by credential orchestration — a rejection never reaches EPIC-09.
            events.publish("VisitorRequestRejected", "visitor_request", requestId,
                    "{\"requestId\":\"" + requestId + "\","
                            + "\"tenantId\":\"" + request.tenantId() + "\","
                            + "\"decidedBy\":\"" + approver + "\","
                            + "\"decidedAt\":\"" + now + "\","
                            + "\"visitorIds\":" + jsonIds(request.visitorIds()) + "}");

            return new RequestDecision(requestId, request.status().dbValue(),
                    approver, now, request.decisionReason());
        });
    }

    private static String jsonIds(List<UUID> ids) {
        return ids.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
