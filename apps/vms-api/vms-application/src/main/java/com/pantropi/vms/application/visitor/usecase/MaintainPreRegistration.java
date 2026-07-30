package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.AuditProjection;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.IssuedCredentials;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.time.Instant;
import java.util.UUID;

/**
 * A floor receptionist corrects or withdraws a pre-registration before arrival
 * (US-08.1.3, T-08.1.3.1/2) — FR-VMS-03 (SRS B1).
 *
 * <h2>Whose records these are is the scope seam's decision, not this class's</h2>
 * The repository read is scoped, and for a floor receptionist {@code ScopePolicy}'s
 * {@code OwnReception} now translates to <em>the tenants on their own floor</em> (ADR-0005). So
 * another floor's pre-registration comes back empty here — indistinguishable from one that never
 * existed (AC-5) — with no floor comparison written in this class that could disagree with the seam.
 *
 * <h2>Cancellation may owe a revocation (AC-3)</h2>
 * EPIC-09 is unbuilt, so nothing issues credentials yet — but the check is real now, because the
 * cancellation path must not need rework the day issuance arrives. A visitor with a live credential
 * gets a {@code CredentialRevocationRequested} in the same transaction as the cancellation, so a
 * withdrawn visitor cannot keep a working pass, and a rolled-back cancellation cannot revoke one.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class MaintainPreRegistration {

    private final VisitorRequestRepository requests;
    private final VisitorTypeDirectory visitorTypes;
    private final IssuedCredentials credentials;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;

    public MaintainPreRegistration(VisitorRequestRepository requests,
                                   VisitorTypeDirectory visitorTypes,
                                   IssuedCredentials credentials, DomainEventPublisher events,
                                   AuditTrail audit, TransactionRunner tx, ClockPort clock) {
        this.requests = requests;
        this.visitorTypes = visitorTypes;
        this.credentials = credentials;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Correct visitor details or move the appointment window (AC-1). Null means "leave alone".
     *
     * @throws RequestDecision.NotFound             unknown visitor, or another floor's (AC-5)
     * @throws VisitorRequest.VisitorNotEditable    checked in, inside, or terminal (AC-4)
     * @throws RequestDecision.DecidedElsewhere     a decision landed while this ran
     */
    public Outcome amend(UUID receptionist, UUID visitorId, Amendment amendment) {
        VisitorRequest request = requests.findByVisitorId(visitorId)
                .orElseThrow(() -> new RequestDecision.NotFound(visitorId));
        RequestStatus current = request.status();

        if (amendment.visitorTypeId() != null
                && !visitorTypes.isSelectable(amendment.visitorTypeId())) {
            throw new SubmitVisitorRequest.UnknownVisitorType();
        }

        boolean windowChanged =
                amendment.appointmentFrom() != null || amendment.appointmentTo() != null;
        if (windowChanged) {
            request.reschedule(new TimeWindow(
                    amendment.appointmentFrom() == null
                            ? request.window().from() : amendment.appointmentFrom(),
                    amendment.appointmentTo() == null
                            ? request.window().to() : amendment.appointmentTo()));
        }

        boolean detailsChanged = amendment.fullName() != null || amendment.email() != null
                || amendment.phone() != null || amendment.company() != null
                || amendment.visitorTypeId() != null;

        // Always through amendVisitor, even for a window-only or empty edit: it is the guard that
        // proves the visitor is still editable (AC-4), and a stale desk view should learn its record
        // moved on rather than receive a 200 that changed nothing. Nulls coalesce against what is
        // stored, so a partial edit does not blank the rest.
        Visitor existing = visitorOn(request, visitorId);
        request.amendVisitor(visitorId,
                amendment.fullName() != null ? amendment.fullName() : existing.fullName(),
                amendment.email() != null ? amendment.email() : existing.emailValue(),
                amendment.phone() != null ? amendment.phone() : existing.phoneValue(),
                amendment.company() != null ? amendment.company() : existing.company(),
                amendment.visitorTypeId() != null
                        ? amendment.visitorTypeId() : existing.visitorTypeId());

        Instant now = clock.now();
        return tx.call(() -> {
            if (!requests.savePreArrivalChange(request, current)) {
                throw new RequestDecision.DecidedElsewhere(request.id());
            }

            // AC-1: before/after, PII-redacted — flags say what kind of change, never the content.
            audit.recordChange(receptionist, "visitor.amend", "visitor", visitorId.toString(),
                    AuditProjection.before(current),
                    AuditProjection.start()
                            .put("status", request.status().dbValue())
                            .put("requestId", request.id())
                            .put("amendedAt", now)
                            .put("detailsChanged", detailsChanged)
                            .put("windowChanged", windowChanged)
                            .put("appointmentFrom", request.window().from())
                            .put("appointmentTo", request.window().to())
                            .json());

            events.publish("VisitorPreRegistrationAmended", "visitor_request", request.id(),
                    "{\"requestId\":\"" + request.id() + "\","
                            + "\"visitorId\":\"" + visitorId + "\","
                            + "\"amendedBy\":\"" + receptionist + "\","
                            + "\"amendedAt\":\"" + now + "\","
                            + "\"appointmentFrom\":\"" + request.window().from() + "\","
                            + "\"appointmentTo\":\"" + request.window().to() + "\"}");

            return new Outcome(request.id(), visitorId, request.status().dbValue(), false, false);
        });
    }

    /**
     * Withdraw one visitor (AC-2, AC-3).
     *
     * @throws RequestDecision.NotFound          unknown visitor, or another floor's (AC-5)
     * @throws VisitorRequest.VisitorNotEditable checked in, inside, or already terminal (AC-4)
     * @throws RequestDecision.DecidedElsewhere  a decision landed while this ran
     */
    public Outcome cancel(UUID receptionist, UUID visitorId) {
        VisitorRequest request = requests.findByVisitorId(visitorId)
                .orElseThrow(() -> new RequestDecision.NotFound(visitorId));
        RequestStatus current = request.status();

        Instant now = clock.now();
        boolean requestCancelled = request.cancelVisitor(visitorId, now);
        // Asked before the transaction opens; answered inside it would be equivalent today, but the
        // question is read-only and keeping it out shortens the transaction.
        boolean followedIssuance = credentials.anyLiveFor(visitorId);

        return tx.call(() -> {
            if (!requests.savePreArrivalChange(request, current)) {
                throw new RequestDecision.DecidedElsewhere(request.id());
            }

            // AC-3: "cancellation followed issuance" is the fact an incident review needs, and the
            // credential row itself may be gone by then.
            audit.recordChange(receptionist, "visitor.cancel", "visitor", visitorId.toString(),
                    AuditProjection.before(current),
                    AuditProjection.start()
                            .put("status", request.status().dbValue())
                            .put("requestId", request.id())
                            .put("cancelledAt", now)
                            .put("requestCancelled", requestCancelled)
                            .put("followedIssuance", followedIssuance)
                            .json());

            events.publish("VisitorCancelled", "visitor_request", request.id(),
                    "{\"requestId\":\"" + request.id() + "\","
                            + "\"visitorId\":\"" + visitorId + "\","
                            + "\"cancelledBy\":\"" + receptionist + "\","
                            + "\"cancelledAt\":\"" + now + "\","
                            + "\"requestCancelled\":" + requestCancelled + "}");

            if (followedIssuance) {
                // AC-3: in the same transaction as the cancellation — a withdrawn visitor must not
                // keep a working pass, and a rolled-back cancellation must not revoke one.
                events.publish("CredentialRevocationRequested", "visitor_request", request.id(),
                        "{\"requestId\":\"" + request.id() + "\","
                                + "\"reason\":\"pre_registration_cancelled\","
                                + "\"requestedAt\":\"" + now + "\","
                                + "\"visitorIds\":[\"" + visitorId + "\"]}");
            }

            return new Outcome(request.id(), visitorId, request.status().dbValue(),
                    requestCancelled, followedIssuance);
        });
    }

    private static Visitor visitorOn(VisitorRequest request, UUID visitorId) {
        return request.visitors().stream()
                .filter(v -> v.id().equals(visitorId))
                .findFirst()
                .orElseThrow(() -> new VisitorRequest.VisitorNotFoundInRequest(visitorId));
    }

    /** A partial edit: null means "leave this alone". No status, tenant, floor or host — ever. */
    public record Amendment(String fullName, String email, String phone, String company,
                            UUID visitorTypeId, Instant appointmentFrom, Instant appointmentTo) {}

    /**
     * @param requestCancelled true when this visitor was the last active one and took the request
     *                         with them (AC-2)
     * @param revocationRequested true when a live credential existed and its revocation was
     *                            signalled (AC-3)
     */
    public record Outcome(UUID requestId, UUID visitorId, String requestStatus,
                          boolean requestCancelled, boolean revocationRequested) {}
}
