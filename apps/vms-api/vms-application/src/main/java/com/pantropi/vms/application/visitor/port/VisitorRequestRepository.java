package com.pantropi.vms.application.visitor.port;

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
}
