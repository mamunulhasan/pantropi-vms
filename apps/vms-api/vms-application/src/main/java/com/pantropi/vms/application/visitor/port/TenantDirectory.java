package com.pantropi.vms.application.visitor.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the tenant and host references a submission needs (US-07.1.1).
 *
 * <p>Reads only. Managing this master data is EPIC-04 / EPIC-10, both TDD-derived and held pending
 * TODO-01; the underlying tables exist in the baseline schema, so a submission can resolve and
 * validate against them without that management API.
 */
public interface TenantDirectory {

    /** The tenant the user belongs to — the only source of {@code tenant_id} (AC-5). */
    Optional<UUID> tenantOfUser(UUID userId);

    /** True when the host exists, is active, and belongs to the given tenant. */
    boolean hostBelongsToTenant(UUID hostId, UUID tenantId);
}
