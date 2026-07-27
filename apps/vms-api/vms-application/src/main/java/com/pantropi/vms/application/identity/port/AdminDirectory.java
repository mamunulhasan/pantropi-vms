package com.pantropi.vms.application.identity.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for administration queries and the bootstrap insert (US-02.4.1).
 *
 * <p>Implemented by a JDBC adapter over {@code vms.users}, {@code vms.roles},
 * {@code vms.receptions}. The application services below know only this interface.
 */
public interface AdminDirectory {

    long countUsers();

    long countActiveUsersWithRole(String roleCode);

    Optional<UUID> roleIdByCode(String roleCode);

    /** True only when the reception exists and its {@code is_central} flag is set. */
    boolean receptionIsCentral(UUID receptionId);

    /** Insert a user. Used only by the first-run bootstrap; throws if the username exists. */
    void insertUser(UUID id, String username, String passwordHash, UUID roleId,
                    UUID receptionId, boolean active);
}
