package com.pantropi.vms.domain.visitor;

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

    private final UUID id;
    private final UUID tenantId;
    private final UUID hostId;
    private final UUID requestedBy;
    private final VisitKind visitKind;
    private final TimeWindow window;
    private final String purpose;
    private final List<Visitor> visitors;
    private RequestStatus status;
    private UUID approvedBy;

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
        String cleanPurpose = purpose == null || purpose.isBlank() ? null : purpose.trim();
        if (cleanPurpose != null && cleanPurpose.length() > MAX_PURPOSE) {
            throw new IllegalArgumentException("The purpose must be at most " + MAX_PURPOSE
                    + " characters");
        }
        return new VisitorRequest(UUID.randomUUID(), tenantId, hostId, requestedBy,
                VisitKind.PRE_SCHEDULED, window, cleanPurpose, new ArrayList<>(visitors),
                RequestStatus.SUBMITTED, null);
    }

    /** Rehydrate from storage without re-running submission rules. */
    public static VisitorRequest rehydrate(UUID id, UUID tenantId, UUID hostId, UUID requestedBy,
                                           VisitKind visitKind, TimeWindow window, String purpose,
                                           List<Visitor> visitors, RequestStatus status,
                                           UUID approvedBy) {
        return new VisitorRequest(id, tenantId, hostId, requestedBy, visitKind, window, purpose,
                new ArrayList<>(visitors), status, approvedBy);
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

    /** FM Admin approval (FR-VMS-02); approves every named visitor with the request. */
    public void approve(UUID approver) {
        requirePending("approve");
        this.status = RequestStatus.APPROVED;
        this.approvedBy = Objects.requireNonNull(approver, "approver is required");
        visitors.forEach(Visitor::markApproved);
    }

    /** FM Admin rejection (FR-VMS-02). */
    public void reject(UUID approver) {
        requirePending("reject");
        this.status = RequestStatus.REJECTED;
        this.approvedBy = Objects.requireNonNull(approver, "approver is required");
        visitors.forEach(Visitor::markCancelled);
    }

    public void cancel() {
        requirePending("cancel");
        this.status = RequestStatus.CANCELLED;
        visitors.forEach(Visitor::markCancelled);
    }

    private void requirePending(String action) {
        if (status != RequestStatus.SUBMITTED) {
            throw new RequestNotPending(action, status);
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

    public static final class RequestNotPending extends IllegalStateException {
        public RequestNotPending(String action, RequestStatus status) {
            super("Cannot " + action + " a request that is already " + status.name().toLowerCase());
        }
    }
}
