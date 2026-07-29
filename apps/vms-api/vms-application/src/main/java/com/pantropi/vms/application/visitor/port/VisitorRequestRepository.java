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
}
