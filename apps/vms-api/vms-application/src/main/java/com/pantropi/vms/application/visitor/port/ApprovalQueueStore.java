package com.pantropi.vms.application.visitor.port;

import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.domain.visitor.RequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for the approval queue (US-07.3.1, T-07.3.1.1) — FR-VMS-02 (SRS B1).
 *
 * <p>A separate port from {@link VisitorRequestRepository} rather than another method on it. The
 * repository loads the aggregate to change it; this answers a screen. Sharing one interface would
 * mean the queue rehydrating full {@code VisitorRequest} objects — every visitor, every field — to
 * display a count, and the projection below could not stay narrow.
 *
 * <h2>The projection is the security control</h2>
 * The queue shows a tenant, a host, a window and <em>how many</em> people are coming. It does not
 * show who they are. Visitor email, phone and identity-document reference are not omitted from the
 * response by the controller — they are never selected, so no serialisation mistake downstream can
 * put them on a screen that dozens of approvers have open all day. Names of individual visitors sit
 * behind the detail read, under its own authorisation.
 */
public interface ApprovalQueueStore {

    /**
     * Requests awaiting a decision, newest first, subject to the caller's scope.
     *
     * <p>The implementation obtains its predicate from the scoping policy (AC-6) — it does not write
     * a tenant condition of its own.
     */
    Page pending(Filter filter);

    /**
     * What to narrow the queue by (US-07.3.2). Every field is optional; all supplied ones combine
     * conjunctively (AC-1).
     *
     * <p><strong>The scope predicate is not here, and cannot be.</strong> It comes from the policy
     * inside the adapter and is applied before any of this, so a filter can only ever narrow what
     * the caller may already see (AC-5). A tenant id in this record selects <em>within</em> the
     * caller's scope; it does not reach outside it.
     *
     * @param status   which status to list; {@code null} means the pending default — see
     *                 {@link com.pantropi.vms.application.visitor.usecase.PendingApprovals}
     * @param nameLike substring to match against visitor and host names, case-insensitively
     */
    record Filter(RequestStatus status, UUID tenantId, Instant visitFrom, Instant visitTo,
                  String nameLike, PageRequest page) {}

    /**
     * One row of the queue. Counts and identifiers, plus the two names an approver needs to
     * recognise the request at a glance — never a visitor's contact details.
     */
    record PendingRequest(UUID id, UUID tenantId, String tenantName, UUID hostId, String hostName,
                          Instant scheduledFrom, Instant scheduledTo, int visitorCount,
                          Instant submittedAt) {}

    /** @param size the size actually applied after clamping (AC-5) */
    record Page(List<PendingRequest> content, long totalElements, int page, int size) {}
}
