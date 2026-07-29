package com.pantropi.vms.application.masterdata.port;

import java.util.UUID;

/**
 * What still depends on a tenant, asked before it is retired (US-04.3.1, T-04.3.1.2, AC-3).
 *
 * <p>Separate from {@link MasterDataStore} because it is not a master data operation: it reads
 * {@code vms.users}, which belongs to the identity context. Putting it on the shared store would
 * have given every entity a method that only one of them can answer.
 *
 * <p>The count is a <strong>snapshot for the administrator to look at</strong>, not a gate. It is
 * one statement, so it is consistent at the moment it is read, but a user can be assigned to the
 * tenant a millisecond later. Deactivating a tenant with users attached is not forbidden — the
 * requirement is that the administrator is told, and then decides.
 */
public interface TenantDependencies {

    /** Active users currently assigned to this tenant. */
    int activeUserCount(UUID tenantId);
}
