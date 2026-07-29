package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.RoleGrantStore;
import com.pantropi.vms.application.identity.usecase.EffectivePermissions;
import com.pantropi.vms.domain.identity.PermissionSet;
import com.pantropi.vms.domain.identity.Permissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link EffectivePermissions} (US-03.1.1, T-03.1.1.3) — pure, fake port. */
class EffectivePermissionsTest {

    private static final UUID ACTIVE = UUID.randomUUID();
    private static final UUID DEACTIVATED = UUID.randomUUID();

    private final FakeStore store = new FakeStore();
    private final EffectivePermissions permissions = new EffectivePermissions(store);

    @Test
    @DisplayName("AC-2: a user's permissions come from their role's grants")
    void resolvesFromTheRole() {
        store.activeRoles.put(ACTIVE, "SYSTEM_ADMIN");
        store.grants.put("SYSTEM_ADMIN", Set.of(Permissions.USER_MANAGE,
                Permissions.MASTERDATA_EDIT));

        PermissionSet resolved = permissions.forUser(ACTIVE);

        assertThat(resolved.allows(Permissions.USER_MANAGE)).isTrue();
        assertThat(resolved.allows(Permissions.MASTERDATA_EDIT)).isTrue();
        assertThat(resolved.allows(Permissions.SETTINGS_MANAGE)).isFalse();
    }

    @Test
    @DisplayName("AC-2: a deactivated user resolves to no permissions at all")
    void deactivatedUserResolvesEmpty() {
        // Not "their role's permissions minus something" — nothing. The store returns no role for
        // an inactive user, so the empty set falls out rather than needing a special case.
        store.grants.put("SYSTEM_ADMIN", Set.of(Permissions.USER_MANAGE));

        assertThat(permissions.forUser(DEACTIVATED).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("AC-5: a role with no grants denies everything, rather than defaulting open")
    void ungrantedRoleFailsClosed() {
        store.activeRoles.put(ACTIVE, "TENANT");
        // No entry in grants at all — the role exists and has been given nothing.

        PermissionSet resolved = permissions.forUser(ACTIVE);

        assertThat(resolved.isEmpty()).isTrue();
        for (String code : Permissions.ALL) {
            assertThat(resolved.allows(code)).as("allows %s", code).isFalse();
        }
    }

    @Test
    @DisplayName("an unknown user and a null id both resolve to nothing")
    void unknownResolvesEmpty() {
        assertThat(permissions.forUser(UUID.randomUUID()).isEmpty()).isTrue();
        assertThat(permissions.forUser(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("an unknown role resolves to nothing rather than throwing")
    void unknownRoleResolvesEmpty() {
        assertThat(permissions.forRole("NO_SUCH_ROLE").isEmpty()).isTrue();
        assertThat(permissions.forRole(null).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("a resolved set cannot be widened after the decision that used it")
    void resolvedSetIsImmutable() {
        store.activeRoles.put(ACTIVE, "TENANT");
        store.grants.put("TENANT", Set.of(Permissions.VISITOR_REQUEST));

        PermissionSet resolved = permissions.forUser(ACTIVE);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> resolved.codes().add(Permissions.USER_MANAGE))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("PermissionSet.NONE allows nothing, including a null code")
    void noneAllowsNothing() {
        assertThat(PermissionSet.NONE.allows(Permissions.USER_MANAGE)).isFalse();
        assertThat(PermissionSet.NONE.allows(null)).isFalse();
        assertThat(PermissionSet.of(null).isEmpty()).isTrue();
        assertThat(PermissionSet.of(Set.of()).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("the curated constant set names every permission exactly once")
    void constantsAreWellFormed() {
        assertThat(Permissions.ALL).hasSize(11);
        assertThat(Permissions.isKnown(Permissions.CREDENTIAL_OVERRIDE)).isTrue();
        assertThat(Permissions.isKnown("masterdata.veiw")).isFalse();   // the typo this prevents
        assertThat(Permissions.isKnown(null)).isFalse();
        // The unbacked three are part of the vocabulary but not of any grant — see V9.
        assertThat(Permissions.ALL).containsAll(Permissions.UNBACKED);
    }

    private static final class FakeStore implements RoleGrantStore {
        final Map<UUID, String> activeRoles = new LinkedHashMap<>();
        final Map<String, Set<String>> grants = new LinkedHashMap<>();

        public List<Role> roles() { return List.of(); }
        public Optional<Role> roleByCode(String code) { return Optional.empty(); }
        public Set<String> permissionCodes() { return Permissions.ALL; }
        public Set<String> grantsFor(String roleCode) {
            return grants.getOrDefault(roleCode, Set.of());
        }
        public Map<String, Set<String>> allGrants() { return grants; }
        public Optional<String> activeUserRole(UUID userId) {
            return Optional.ofNullable(activeRoles.get(userId));
        }
        public List<Permission> permissionCatalogue() { return List.of(); }
        public Map<String, Integer> activeUserCountsByRole() { return Map.of(); }
        public Map<String, Integer> lockActiveUserCountsByRole() { return Map.of(); }
        public void replaceGrants(String roleCode, Set<String> codes) {
            grants.put(roleCode, codes);
        }
    }
}
