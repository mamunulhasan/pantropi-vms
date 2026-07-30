package com.pantropi.vms.application.visitor.port;

import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the {@link VisitorRequest} aggregate (US-07.1.1, T-07.1.1.2).
 *
 * <p>The aggregate is saved as one unit — request and visitors together — so a partially written
 * request cannot exist.
 */
public interface VisitorRequestRepository {

    void save(VisitorRequest request);

    Optional<VisitorRequest> findById(UUID id);

    /**
     * Persists a decision, but only if the stored request is still in the state it was decided from
     * (US-07.4.1, T-07.4.1.2, AC-6).
     *
     * <p>This is the compare-and-set that makes concurrent approvals safe. Two FM Admins can both
     * load a {@code submitted} request and both pass the aggregate's guard — they are working on
     * separate copies, so neither sees the other. The database is the only place they meet, so the
     * expected state travels into the {@code WHERE} clause and the second write matches no row.
     *
     * <p>The caller must treat {@code false} as "someone else decided this first" and abandon the
     * transaction, so the audit entry and the outbox event roll back with it: exactly one winner
     * means exactly one event, which is what stops EPIC-09 issuing two credentials for one visit.
     *
     * @param expectedPrevious the status the aggregate was in before the decision was applied
     * @return true if this caller's decision was the one that landed
     */
    boolean saveDecision(VisitorRequest request, RequestStatus expectedPrevious);

    /**
     * Persists an amendment, but only if the request is still in the state it was amended from
     * (US-07.1.3, T-07.1.3.2).
     *
     * <p>The same compare-and-set as {@link #saveDecision}, for the same reason: an FM Admin may be
     * approving the request while the tenant edits it, and the amended content must not land on a
     * request that has since been decided — the decision would then be a record of something that no
     * longer exists.
     *
     * <p>Replaces the visitor rows wholesale rather than diffing them. A visitor has no
     * tenant-visible identity to preserve across an edit, and matching old rows to new ones by name
     * would silently merge two people who happen to share one.
     *
     * @return true if the amendment was the write that landed
     */
    boolean saveAmendment(VisitorRequest request, RequestStatus expectedCurrent);

    /**
     * The request one of whose visitors this is, subject to the caller's scope (US-08.1.3).
     *
     * <p>Empty for an unknown visitor and for one outside the caller's scope alike — for a floor
     * receptionist that scope is the tenants on their own floor (ADR-0005), so another floor's
     * pre-registration is indistinguishable from one that never existed (AC-5).
     */
    Optional<VisitorRequest> findByVisitorId(UUID visitorId);

    /**
     * Persists a pre-arrival change — amended details, a new window, a visitor-level cancellation —
     * against the request state it was made from (US-08.1.3, T-08.1.3.2).
     *
     * <p>The same compare-and-set as {@link #saveDecision}: a receptionist editing while an FM Admin
     * decides must produce one winner, and the loser's audit and outbox writes roll back with it.
     *
     * <p>Visitor rows are updated <strong>in place</strong>, never deleted and re-inserted.
     * {@code vms.credentials.visitor_id} is {@code ON DELETE CASCADE}, so a delete-and-reinsert of
     * the same visitor id would silently destroy the credential row — precisely the record AC-3
     * needs intact to know a revocation is owed.
     */
    boolean savePreArrivalChange(VisitorRequest request, RequestStatus expectedCurrent);
}
