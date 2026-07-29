package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.RoleGrantStore;
import com.pantropi.vms.domain.identity.PermissionSet;

import java.util.UUID;

/**
 * Resolves what a principal may actually do (US-03.1.1, T-03.1.1.3, AC-2).
 *
 * <p>Permissions come from the user's role, through {@code vms.role_permissions}. Nothing is
 * hard-coded against a role name, which is the point of the story: an access decision asks whether a
 * permission is held, never whether someone "is an admin".
 *
 * <h2>Fails closed, in every direction</h2>
 * An unknown user, a deactivated user and a role with no grants all resolve to
 * {@link PermissionSet#NONE}. Each of those is a legitimate answer meaning "may do nothing", not an
 * error to be recovered from with a default — and a default here would be a permission the operator
 * never granted (AC-5).
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class EffectivePermissions {

    private final RoleGrantStore store;

    public EffectivePermissions(RoleGrantStore store) {
        this.store = store;
    }

    /** The permissions of an active user. {@link PermissionSet#NONE} if inactive or unknown. */
    public PermissionSet forUser(UUID userId) {
        if (userId == null) {
            return PermissionSet.NONE;
        }
        return store.activeUserRole(userId)
                .map(this::forRole)
                .orElse(PermissionSet.NONE);
    }

    /** The permissions a role carries. {@link PermissionSet#NONE} for an unknown or ungranted role. */
    public PermissionSet forRole(String roleCode) {
        if (roleCode == null) {
            return PermissionSet.NONE;
        }
        return PermissionSet.of(store.grantsFor(roleCode));
    }
}
