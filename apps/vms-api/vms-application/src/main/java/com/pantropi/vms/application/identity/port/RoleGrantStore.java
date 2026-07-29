package com.pantropi.vms.application.identity.port;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port over {@code vms.roles}, {@code vms.permissions} and {@code vms.role_permissions}
 * (US-03.1.1, T-03.1.1.1).
 */
public interface RoleGrantStore {

    /** Every seeded role, ordered by code. */
    List<Role> roles();

    Optional<Role> roleByCode(String code);

    /** Every permission code the database knows — used to verify the curated constants (AC-4). */
    Set<String> permissionCodes();

    /** Permission codes granted to a role. Empty, never null, for a role with no grants. */
    Set<String> grantsFor(String roleCode);

    /** The whole matrix at once, for administration screens and the grant-matrix assertion. */
    Map<String, Set<String>> allGrants();

    /**
     * The role of an <strong>active</strong> user (AC-2). Empty for an unknown or deactivated one,
     * so the caller resolves to no permissions rather than the role's.
     */
    Optional<String> activeUserRole(UUID userId);

    record Role(UUID id, String code, String name, String description) {}
}
