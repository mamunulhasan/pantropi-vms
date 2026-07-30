package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.RoleGrantStore;
import com.pantropi.vms.application.identity.port.UserAdministrationStore;
import com.pantropi.vms.application.identity.usecase.CurrentUser;
import com.pantropi.vms.application.identity.usecase.EffectivePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UNIT TESTS for {@link CurrentUser} (US-06.3.2, T-06.3.2.1).
 *
 * <p>The profile is what the portal boots its navigation from, so the properties under test are
 * the security-relevant ones: permissions come from the database (not the token), the list is
 * deterministic, and every missing-data path fails closed to an empty list rather than erroring
 * or inventing authority.
 */
class CurrentUserTest {

    private static final UUID USER = UUID.randomUUID();

    private final FakeUsers users = new FakeUsers();
    private final FakeGrants grants = new FakeGrants();
    private final CurrentUser currentUser = new CurrentUser(users, new EffectivePermissions(grants));

    @Test
    @DisplayName("AC: the profile carries display name and the sorted effective permission set")
    void profileCarriesDisplayNameAndPermissions() {
        users.view = new UserAdministrationStore.UserView(USER, "sysadmin", "s@ex.com",
                "Sydney Admin", "SYSTEM_ADMIN", null, null, true);
        grants.role = "SYSTEM_ADMIN";
        grants.grants = Set.of("user.manage", "audit.view", "masterdata.edit");

        CurrentUser.Profile p = currentUser.profile(USER, "sysadmin", "SYSTEM_ADMIN");

        assertThat(p.displayName()).isEqualTo("Sydney Admin");
        // Sorted: the payload is deterministic, so a client equality check is a string compare.
        assertThat(p.permissions())
                .isEqualTo(List.of("audit.view", "masterdata.edit", "user.manage"));
    }

    @Test
    @DisplayName("permissions are resolved live from the user's stored role, not the token role")
    void permissionsComeFromStoredRoleNotToken() {
        users.view = new UserAdministrationStore.UserView(USER, "worker", "w@ex.com",
                "Wes Worker", "TENANT", null, null, true);
        grants.role = "TENANT"; // the database says TENANT, whatever an old token claims
        grants.grants = Set.of("visitor.register");

        CurrentUser.Profile p = currentUser.profile(USER, "worker", "SYSTEM_ADMIN");

        assertThat(p.permissions()).containsExactly("visitor.register");
    }

    @Test
    @DisplayName("fail closed: a deleted user gets the username as display name and no permissions")
    void deletedUserFailsClosed() {
        users.view = null;
        grants.role = null;

        CurrentUser.Profile p = currentUser.profile(USER, "ghost", "TENANT");

        assertThat(p.displayName()).isEqualTo("ghost");
        assertThat(p.permissions()).isEmpty();
    }

    @Test
    @DisplayName("fail closed: a deactivated user keeps a name but resolves to no permissions")
    void deactivatedUserHasNoPermissions() {
        users.view = new UserAdministrationStore.UserView(USER, "gone", "g@ex.com",
                "Gone Person", "TENANT", null, null, false);
        grants.role = null; // activeUserRole answers empty for an inactive user

        CurrentUser.Profile p = currentUser.profile(USER, "gone", "TENANT");

        assertThat(p.displayName()).isEqualTo("Gone Person");
        assertThat(p.permissions()).isEmpty();
    }

    // ---- fakes ----

    private static final class FakeUsers implements UserAdministrationStore {
        UserView view;

        @Override public Optional<UserView> findById(UUID id) {
            return Optional.ofNullable(view);
        }

        @Override public boolean usernameExists(String username) { throw new UnsupportedOperationException(); }
        @Override public boolean emailExists(String email) { throw new UnsupportedOperationException(); }
        @Override public Optional<UUID> roleIdByCode(String roleCode) { throw new UnsupportedOperationException(); }
        @Override public boolean receptionActive(UUID receptionId) { throw new UnsupportedOperationException(); }
        @Override public boolean tenantActive(UUID tenantId) { throw new UnsupportedOperationException(); }
        @Override public UUID insert(NewUser u, String placeholderPasswordHash) { throw new UnsupportedOperationException(); }
        @Override public void updateAssignment(UUID id, UUID roleId, UUID receptionId, UUID tenantId) { throw new UnsupportedOperationException(); }
        @Override public void setActive(UUID id, boolean active) { throw new UnsupportedOperationException(); }
        @Override public Page list(UserFilter filter) { throw new UnsupportedOperationException(); }
    }

    private static final class FakeGrants implements RoleGrantStore {
        String role;
        Set<String> grants = Set.of();

        @Override public Optional<String> activeUserRole(UUID userId) {
            return Optional.ofNullable(role);
        }

        @Override public Set<String> grantsFor(String roleCode) {
            return roleCode != null && roleCode.equals(role) ? grants : Set.of();
        }

        @Override public List<Role> roles() { throw new UnsupportedOperationException(); }
        @Override public Optional<Role> roleByCode(String code) { throw new UnsupportedOperationException(); }
        @Override public Set<String> permissionCodes() { throw new UnsupportedOperationException(); }
        @Override public Map<String, Set<String>> allGrants() { throw new UnsupportedOperationException(); }
        @Override public List<Permission> permissionCatalogue() { throw new UnsupportedOperationException(); }
        @Override public Map<String, Integer> activeUserCountsByRole() { throw new UnsupportedOperationException(); }
        @Override public Map<String, Integer> lockActiveUserCountsByRole() { throw new UnsupportedOperationException(); }
        @Override public void replaceGrants(String roleCode, Set<String> permissionCodes) { throw new UnsupportedOperationException(); }
    }
}
