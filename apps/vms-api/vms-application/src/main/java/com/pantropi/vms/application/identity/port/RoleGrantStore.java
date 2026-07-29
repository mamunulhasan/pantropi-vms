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

    /** The permission catalogue with descriptions, for the administration screen (US-03.3.1 AC-1). */
    List<Permission> permissionCatalogue();

    /** Active users per role, for display. Roles with none appear as zero, not absent. */
    Map<String, Integer> activeUserCountsByRole();

    /**
     * The same counts, but <strong>locking those user rows</strong> for the calling transaction
     * (US-03.3.1, T-03.3.1.1).
     *
     * <p>Separate from the display read on purpose: taking a row lock to render a screen would make
     * every administrator looking at the roles page contend with every one changing it.
     *
     * <p>The administrative-lockout guard reads through this so that a grant change and a user
     * deactivation cannot each observe a state the other is about to invalidate. Same reasoning as
     * the Master Admin guard, and for the same reason it must be called inside a transaction.
     *
     * <p>Roles with no active users appear with a count of zero rather than being absent.
     */
    Map<String, Integer> lockActiveUserCountsByRole();

    /**
     * Replace a role's grants with exactly this set (US-03.3.1 AC-2).
     *
     * <p>Replace rather than add/remove deltas: the caller submits the set it wants, which is what
     * the administration screen shows, and applying a delta computed against a stale read is the
     * failure the version token exists to prevent.
     */
    void replaceGrants(String roleCode, Set<String> permissionCodes);

    record Role(UUID id, String code, String name, String description) {}

    record Permission(String code, String description) {}
}
