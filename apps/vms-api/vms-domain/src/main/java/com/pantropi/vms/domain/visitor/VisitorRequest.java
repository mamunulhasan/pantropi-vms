package com.pantropi.vms.domain.visitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root: a tenant's request for one or more guests to visit (US-07.1.1, T-07.1.1.1) —
 * FR-VMS-01, FR-VMS-02 (SRS B1).
 *
 * <p>The consistency boundary for a submission: the request and its visitors are created, and later
 * approved or rejected, as one unit in one transaction. Nothing outside this aggregate may change a
 * {@link Visitor}'s status.
 *
 * <p>Pure Java by construction — no Spring, no JPA, no framework of any kind, enforced by the
 * architecture fitness tests of US-01.2.2. The tenant is set by the caller from the authenticated
 * principal, never from client input (US-07.1.1 AC-5); this class simply refuses to be built
 * without one.
 */
public final class VisitorRequest {

    private static final int MAX_PURPOSE = 500;
    private static final int MAX_VISITORS = 50;

    /** Bounded so a reason stays a reason; the limit is documented on the reject endpoint. */
    private static final int MAX_DECISION_REASON = 1000;

    private final UUID id;
    private final UUID tenantId;
    private final UUID requestedBy;
    private final VisitKind visitKind;
    private final List<Visitor> visitors;

    // Amendable while the request is still awaiting a decision (US-07.1.3 AC-1).
    private UUID hostId;
    private TimeWindow window;
    private String purpose;
    private RequestStatus status;
    private UUID approvedBy;
    private String decisionReason;
    private Instant decidedAt;

    /**
     * Set when a cancellation withdrew an approval that had already been granted
     * (US-07.1.3 AC-3).
     *
     * <p>Recorded on the aggregate rather than left for the caller to infer, because the fact it
     * represents is consequential: an approved request may already have a credential, and that
     * credential has to be revoked. A use case comparing statuses either side of the call would
     * arrive at the same answer today and would have to be reimplemented by the next caller.
     */
    private boolean cancelledAfterApproval;

    private VisitorRequest(UUID id, UUID tenantId, UUID hostId, UUID requestedBy,
                           VisitKind visitKind, TimeWindow window, String purpose,
                           List<Visitor> visitors, RequestStatus status, UUID approvedBy) {
        this.id = id;
        this.tenantId = tenantId;
        this.hostId = hostId;
        this.requestedBy = requestedBy;
        this.visitKind = visitKind;
        this.window = window;
        this.purpose = purpose;
        this.visitors = visitors;
        this.status = status;
        this.approvedBy = approvedBy;
    }

    /**
     * Submit a pre-scheduled request. The result is always {@link RequestStatus#SUBMITTED} with at
     * least one pending visitor — a request with no one to admit is meaningless.
     *
     * @param tenantId    the submitting user's own tenant, resolved server-side (AC-1, AC-5)
     * @param requestedBy the submitting user
     */
    public static VisitorRequest submit(UUID tenantId, UUID hostId, UUID requestedBy,
                                        TimeWindow window, String purpose, List<Visitor> visitors) {
        Objects.requireNonNull(tenantId, "tenantId is resolved from the principal and is required");
        Objects.requireNonNull(requestedBy, "requestedBy is required");
        Objects.requireNonNull(window, "the visit window is required");
        if (visitors == null || visitors.isEmpty()) {
            throw new NoVisitorsNamed();
        }
        if (visitors.size() > MAX_VISITORS) {
            throw new TooManyVisitors(visitors.size(), MAX_VISITORS);
        }
        String cleanPurpose = purpose == null ? null : cleanPurpose(purpose);
        return new VisitorRequest(UUID.randomUUID(), tenantId, hostId, requestedBy,
                VisitKind.PRE_SCHEDULED, window, cleanPurpose, new ArrayList<>(visitors),
                RequestStatus.SUBMITTED, null);
    }

    /** Rehydrate from storage without re-running submission rules. */
    public static VisitorRequest rehydrate(UUID id, UUID tenantId, UUID hostId, UUID requestedBy,
                                           VisitKind visitKind, TimeWindow window, String purpose,
                                           List<Visitor> visitors, RequestStatus status,
                                           UUID approvedBy, String decisionReason,
                                           Instant decidedAt) {
        VisitorRequest request = new VisitorRequest(id, tenantId, hostId, requestedBy, visitKind,
                window, purpose, new ArrayList<>(visitors), status, approvedBy);
        request.decisionReason = decisionReason;
        request.decidedAt = decidedAt;
        return request;
    }

    /**
     * Add a further guest before a decision is taken (FR-VMS-01, group requests).
     *
     * @throws RequestNotPending once the request has been decided
     */
    public void addVisitor(Visitor visitor) {
        requirePending("add a visitor to");
        if (visitors.size() >= MAX_VISITORS) {
            throw new TooManyVisitors(visitors.size() + 1, MAX_VISITORS);
        }
        visitors.add(Objects.requireNonNull(visitor));
    }

    /**
     * FM Admin approval (US-07.4.1, T-07.4.1.1) — FR-VMS-02 (SRS B1); approves every named visitor
     * with the request.
     *
     * <p>Time is a parameter, not {@code Instant.now()}: this class has no clock, and the elapsed-
     * window rule is only testable if "now" is supplied.
     *
     * @param note optional; why the approval was given (AC-4). Blank is the same as absent.
     * @param now  the deciding instant — recorded, and used to refuse an elapsed window
     * @throws RequestTransitions.IllegalTransition from any state but {@code SUBMITTED} (AC-5)
     * @throws WindowAlreadyElapsed                 if the visit window is entirely in the past
     *                                              (AC-7)
     * @throws DecisionReasonTooLong                if the note exceeds the bound
     */
    public void approve(UUID approver, String note, Instant now) {
        Objects.requireNonNull(approver, "approver is required");
        Objects.requireNonNull(now, "the deciding instant is required");
        String cleanNote = cleanReason(note);

        // AC-7: approving a visit that has already finished would mint a credential that is expired
        // the moment it exists. Checked before the transition, so a refusal changes nothing.
        if (window != null && window.to() != null && !window.to().isAfter(now)) {
            throw new WindowAlreadyElapsed(window.to(), now);
        }

        transitionTo(RequestStatus.APPROVED, now);
        this.approvedBy = approver;
        this.decisionReason = cleanNote;
    }

    /**
     * FM Admin rejection with a mandatory reason (FR-VMS-02, SRS B1).
     *
     * <p>The reason is an invariant of the aggregate rather than a check at the edge, so no path —
     * an event handler, a future bulk operation — can record a rejection nobody has to justify.
     *
     * @throws RequestTransitions.IllegalTransition from any state but {@code SUBMITTED}. Reversing
     *                                              an approval is a cancellation, not a rejection.
     * @throws RejectionReasonRequired              if the reason is null, empty or whitespace
     */
    public void reject(UUID approver, String reason, Instant now) {
        Objects.requireNonNull(approver, "approver is required");
        Objects.requireNonNull(now, "the deciding instant is required");
        String trimmed = cleanReason(reason);
        if (trimmed == null) {
            throw new RejectionReasonRequired();
        }
        transitionTo(RequestStatus.REJECTED, now);
        this.approvedBy = approver;
        this.decisionReason = trimmed;
    }

    /**
     * Withdraw the request. Legal from {@code SUBMITTED} and from {@code APPROVED} — an approval can
     * be withdrawn, which is a different fact from having been refused.
     *
     * @throws RequestTransitions.IllegalTransition from a terminal state
     */
    public void cancel(Instant now) {
        Objects.requireNonNull(now, "the deciding instant is required");
        boolean wasApproved = this.status == RequestStatus.APPROVED;
        transitionTo(RequestStatus.CANCELLED, now);
        // After the guard, so a refused cancellation cannot leave the flag set.
        this.cancelledAfterApproval = wasApproved;
    }

    /**
     * Amend a request that has not yet been decided (US-07.1.3, T-07.1.3.1, AC-1).
     *
     * <p>Every argument is optional: null means "leave this as it is", which is what makes a partial
     * update expressible. The one thing that cannot be said this way is <em>clearing</em> the host,
     * since a null host is indistinguishable from an absent one — removing a host is not supported by
     * this story, and a caller that needs it should say so rather than discover the omission.
     *
     * <h2>Not a status change, so not the state machine's business</h2>
     * {@link RequestTransitions} governs moves between statuses. An amendment does not move one; it
     * changes what the request says while it stays where it is. The rule is the narrower
     * {@link #requirePending} — the same guard that governs adding a visitor, and for the same
     * reason: once someone has decided on a request, altering what they decided on would make the
     * decision a record of something that no longer exists (AC-4).
     *
     * @throws RequestNotPending from any state but {@code SUBMITTED}
     */
    public void amend(UUID newHostId, TimeWindow newWindow, String newPurpose,
                      List<Visitor> newVisitors) {
        requirePending("amend");

        // Validate everything before mutating anything, so a rejected amendment leaves the request
        // exactly as it was rather than half-applied.
        String cleanPurpose = newPurpose == null ? null : cleanPurpose(newPurpose);
        if (newVisitors != null) {
            if (newVisitors.isEmpty()) {
                throw new NoVisitorsNamed();
            }
            if (newVisitors.size() > MAX_VISITORS) {
                throw new TooManyVisitors(newVisitors.size(), MAX_VISITORS);
            }
        }

        if (newHostId != null) {
            this.hostId = newHostId;
        }
        if (newWindow != null) {
            this.window = newWindow;
        }
        if (cleanPurpose != null) {
            this.purpose = cleanPurpose;
        }
        if (newVisitors != null) {
            this.visitors.clear();
            this.visitors.addAll(newVisitors);
        }
    }

    /** True when this cancellation withdrew an approval, so a credential may need revoking. */
    public boolean cancelledAfterApproval() {
        return cancelledAfterApproval;
    }

    /**
     * Trims a decision reason, mapping blank to absent.
     *
     * @return null when nothing was said — so a caller that requires one can simply test for null
     * @throws DecisionReasonTooLong if it exceeds the bound
     */
    private static String cleanReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_DECISION_REASON) {
            throw new DecisionReasonTooLong(MAX_DECISION_REASON);
        }
        return trimmed;
    }

    /** One purpose rule, shared by submission and amendment. Blank means absent. */
    private static String cleanPurpose(String purpose) {
        String trimmed = purpose.isBlank() ? null : purpose.trim();
        if (trimmed != null && trimmed.length() > MAX_PURPOSE) {
            throw new IllegalArgumentException("The purpose must be at most " + MAX_PURPOSE
                    + " characters");
        }
        return trimmed;
    }

    /** Visitors may only be added while the request is still open — not a status change. */
    private void requirePending(String action) {
        if (status != RequestStatus.SUBMITTED) {
            throw new RequestNotPending(action, status);
        }
    }

    /**
     * The single point at which this aggregate's status changes (US-07.5.1 AC-4).
     *
     * <p>Guard first, then mutate, then cascade — so an illegal move leaves the request and every
     * visitor exactly as they were.
     */
    private void transitionTo(RequestStatus target, Instant now) {
        RequestTransitions.require(this.status, target);
        this.status = target;
        this.decidedAt = now;
        cascadeToVisitors(target);
    }

    /**
     * Applies the decision to each visitor, leaving terminal ones alone (AC-3).
     *
     * <p>A visitor cancelled individually before the decision is not resurrected by approving the
     * request: someone deliberately removed that person, and approving the visit as a whole is not
     * a decision about them.
     */
    private void cascadeToVisitors(RequestStatus target) {
        for (Visitor visitor : visitors) {
            VisitorCascade.targetFor(target, visitor.status())
                    .ifPresent(visitor::moveTo);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID hostId() {
        return hostId;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public VisitKind visitKind() {
        return visitKind;
    }

    public TimeWindow window() {
        return window;
    }

    public String purpose() {
        return purpose;
    }

    public RequestStatus status() {
        return status;
    }

    public UUID approvedBy() {
        return approvedBy;
    }

    /** Unmodifiable: visitors change only through this aggregate. */
    public List<Visitor> visitors() {
        return Collections.unmodifiableList(visitors);
    }

    // ---- domain failures ----

    public static final class NoVisitorsNamed extends IllegalArgumentException {
        public NoVisitorsNamed() {
            super("A visitor request must name at least one visitor");
        }
    }

    public static final class TooManyVisitors extends IllegalArgumentException {
        public TooManyVisitors(int given, int max) {
            super("A visitor request may name at most " + max + " visitors (given " + given + ")");
        }
    }

    /** Why the request was decided the way it was; null when nobody said (US-07.4.1 AC-4). */
    public String decisionReason() {
        return decisionReason;
    }

    /** When the decision was taken; null while the request is still awaiting one. */
    public Instant decidedAt() {
        return decidedAt;
    }

    /** The visitors this decision applies to — ids only, so a caller cannot leak PII by accident. */
    public List<UUID> visitorIds() {
        return visitors.stream().map(Visitor::id).toList();
    }

    /** A rejection nobody has to justify is not an accountable decision (US-07.4.2 AC-2). */
    public static final class RejectionReasonRequired extends IllegalArgumentException {
        public RejectionReasonRequired() {
            super("A rejection must say why");
        }
    }

    public static final class DecisionReasonTooLong extends IllegalArgumentException {
        public DecisionReasonTooLong(int max) {
            super("A decision reason must be at most " + max + " characters");
        }
    }

    /**
     * A visit that has already finished cannot be approved (US-07.4.1 AC-7).
     *
     * <p>Not an illegal transition — the source state was fine, the world moved on. The distinction
     * is what lets the endpoint answer 422 rather than 409: retrying will never help.
     */
    public static final class WindowAlreadyElapsed extends IllegalStateException {
        public final Instant windowEnd;

        public WindowAlreadyElapsed(Instant windowEnd, Instant now) {
            super("The visit window ended at " + windowEnd + ", before " + now
                    + "; approving it would issue a credential that is already expired");
            this.windowEnd = windowEnd;
        }
    }

    public static final class RequestNotPending extends IllegalStateException {
        public RequestNotPending(String action, RequestStatus status) {
            super("Cannot " + action + " a request that is already " + status.name().toLowerCase());
        }
    }
}
