package com.pantropi.vms.application.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for user administration over {@code vms.users} (US-02.2.1).
 *
 * <p>Never accepts or returns a password hash — provisioning sets an unusable placeholder and
 * activation happens out of band (AC-1).
 */
public interface UserAdministrationStore {

    boolean usernameExists(String username);   // case-insensitive (citext)

    boolean emailExists(String email);          // case-insensitive (citext)

    Optional<UUID> roleIdByCode(String roleCode);

    boolean receptionActive(UUID receptionId);

    boolean tenantActive(UUID tenantId);

    UUID insert(NewUser user, String placeholderPasswordHash);

    Optional<UserView> findById(UUID id);

    void updateAssignment(UUID id, UUID roleId, UUID receptionId, UUID tenantId);

    void setActive(UUID id, boolean active);

    /** Server-side pagination + filtering + sorting; never loads all rows (AC-4). */
    Page list(UserFilter filter);

    // ---- data shapes (no password anywhere) ----

    record NewUser(String username, String email, String fullName, String roleCode,
                   UUID receptionId, UUID tenantId) {}

    record UserView(UUID id, String username, String email, String fullName, String roleCode,
                    UUID receptionId, UUID tenantId, boolean active) {}

    /**
     * @param tenantId narrows to one tenant organisation's accounts. Added because the tenant list
     *                 could report a dependent <em>count</em> and nothing else, so "show me the
     *                 users of tenant X" — the question an administrator actually asks before
     *                 deactivating one — had no answer anywhere in the API.
     */
    record UserFilter(String roleCode, UUID receptionId, UUID tenantId, Boolean active,
                      int page, int size, String sort) {}

    record Page(List<UserView> content, long totalElements, int page, int size) {}
}
