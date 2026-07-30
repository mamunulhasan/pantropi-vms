package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.UserAdministrationStore;

import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal's own profile (US-06.3.2, T-06.3.2.1).
 *
 * <p>The portal's navigation binds to permission codes, so the session bootstrap must deliver the
 * permission set in one round trip — a nav that fetched permissions lazily would render, reflow,
 * and hand an observer a timing oracle for what the user may do. This use case composes what
 * {@code /auth/me} answers: identity from the token, display name and permissions from the store.
 *
 * <h2>Live data, not token data</h2>
 * The token carries identity; it must not carry authority. Permissions are resolved against the
 * database on every call ({@link EffectivePermissions#forUser}), so a grant edit or a deactivation
 * lands on the next profile read, not at the next login. A deactivated or deleted user resolves to
 * an empty permission list — the same fail-closed answer the authorization interceptor gives.
 *
 * <p>A deleted user still gets a profile (the session outlives the row until it expires or the
 * interceptor refuses it): display name falls back to the username, permissions are empty. What it
 * never does is invent authority to smooth over a missing row.
 */
public final class CurrentUser {

    private final UserAdministrationStore users;
    private final EffectivePermissions permissions;

    public CurrentUser(UserAdministrationStore users, EffectivePermissions permissions) {
        this.users = users;
        this.permissions = permissions;
    }

    /** The profile for the principal the token authenticated. Never null; fails closed. */
    public Profile profile(UUID userId, String username, String roleCode) {
        String displayName = users.findById(userId)
                .map(UserAdministrationStore.UserView::fullName)
                .orElse(username);
        List<String> codes = permissions.forUser(userId).codes().stream().sorted().toList();
        return new Profile(userId, username, roleCode, displayName, codes);
    }

    /**
     * @param permissions sorted permission codes — sorted so the payload is deterministic and a
     *                    client-side equality check ("did my permissions change?") is a string compare
     */
    public record Profile(UUID userId, String username, String role, String displayName,
                          List<String> permissions) {}
}
