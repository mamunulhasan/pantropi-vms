package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.RoleGrantStore;
import com.pantropi.vms.application.identity.usecase.RoleAdministration;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.domain.identity.GrantVersion;
import com.pantropi.vms.domain.identity.Permissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link RoleAdministration} (US-03.3.1) — pure, fake ports. */
class RoleAdministrationTest {

    private static final UUID ACTOR = UUID.randomUUID();

    private final FakeStore store = new FakeStore();
    private final RecordingAudit audit = new RecordingAudit();
    private final RoleAdministration roles =
            new RoleAdministration(store, new DirectTransactions(), audit);

    private String versionOf(String roleCode) {
        return GrantVersion.of(store.grants.get(roleCode));
    }

    @Test
    @DisplayName("AC-1: the overview carries grants, versions, user counts and the catalogue")
    void overview() {
        RoleAdministration.Overview overview = roles.overview();

        assertThat(overview.roles()).extracting(RoleAdministration.RoleView::code)
                .containsExactly("FM_ADMIN", "SYSTEM_ADMIN", "TENANT");
        assertThat(overview.catalogue()).isNotEmpty();

        RoleAdministration.RoleView systemAdmin = overview.roles().stream()
                .filter(r -> r.code().equals("SYSTEM_ADMIN")).findFirst().orElseThrow();
        assertThat(systemAdmin.permissions()).contains(Permissions.USER_MANAGE);
        assertThat(systemAdmin.version()).isNotBlank();
        assertThat(systemAdmin.activeUsers()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-2: a grant change is applied and audited with both sets")
    void changeIsAudited() {
        roles.replaceGrants(ACTOR, "TENANT",
                Set.of(Permissions.VISITOR_REQUEST, Permissions.MASTERDATA_VIEW),
                versionOf("TENANT"));

        assertThat(store.grants.get("TENANT"))
                .containsExactlyInAnyOrder(Permissions.VISITOR_REQUEST, Permissions.MASTERDATA_VIEW);
        assertThat(audit.actions()).containsExactly("role.grants_changed");
        assertThat(String.valueOf(audit.entries.get(0)[4])).contains("visitor.request");
        assertThat(String.valueOf(audit.entries.get(0)[5])).contains("masterdata.view");
    }

    @Test
    @DisplayName("revoking is just as available as granting — the whole set is submitted")
    void revokeByOmission() {
        roles.replaceGrants(ACTOR, "TENANT", Set.of(), versionOf("TENANT"));

        assertThat(store.grants.get("TENANT")).isEmpty();
    }

    // ---- AC-5: optimistic concurrency ----

    @Test
    @DisplayName("AC-5: a stale version is refused and changes nothing")
    void staleVersionRefused() {
        String stale = versionOf("TENANT");
        roles.replaceGrants(ACTOR, "TENANT", Set.of(Permissions.MASTERDATA_VIEW), stale);
        audit.entries.clear();

        // A second administrator, still holding the token from before the first change.
        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "TENANT",
                Set.of(Permissions.VISITOR_REGISTER), stale))
                .isInstanceOf(RoleAdministration.StaleGrantVersion.class);

        assertThat(store.grants.get("TENANT"))
                .containsExactly(Permissions.MASTERDATA_VIEW);   // the first change stands
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("AC-5: the conflict carries the current state, not just a refusal")
    void conflictCarriesCurrentState() {
        String stale = versionOf("TENANT");
        roles.replaceGrants(ACTOR, "TENANT", Set.of(Permissions.MASTERDATA_VIEW), stale);

        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "TENANT", Set.of(), stale))
                .isInstanceOfSatisfying(RoleAdministration.StaleGrantVersion.class, e -> {
                    // Being told only that you lost leaves you to reload and guess what changed.
                    assertThat(e.currentPermissions).containsExactly(Permissions.MASTERDATA_VIEW);
                    assertThat(e.currentVersion).isEqualTo(versionOf("TENANT"));
                });
    }

    @Test
    @DisplayName("a missing or wrong version is refused — the token is not optional")
    void versionIsRequired() {
        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "TENANT", Set.of(), null))
                .isInstanceOf(RoleAdministration.StaleGrantVersion.class);
        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "TENANT", Set.of(), "not-a-version"))
                .isInstanceOf(RoleAdministration.StaleGrantVersion.class);
    }

    @Test
    @DisplayName("the version depends on the set, not the order it is written in")
    void versionIsOrderIndependent() {
        assertThat(GrantVersion.of(new LinkedHashSet<>(List.of("a", "b"))))
                .isEqualTo(GrantVersion.of(new LinkedHashSet<>(List.of("b", "a"))));
        assertThat(GrantVersion.of(Set.of("a"))).isNotEqualTo(GrantVersion.of(Set.of("b")));
        assertThat(GrantVersion.of(Set.of())).isNotBlank();
    }

    // ---- AC-4: administrative lockout ----

    @Test
    @DisplayName("AC-4: revoking the last user.manage is refused, and changes nothing")
    void lockoutRefused() {
        // SYSTEM_ADMIN is the only role holding user.manage, and it has active users.
        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "SYSTEM_ADMIN",
                Set.of(Permissions.MASTERDATA_VIEW), versionOf("SYSTEM_ADMIN")))
                .isInstanceOf(RoleAdministration.AdministrativeLockout.class);

        assertThat(store.grants.get("SYSTEM_ADMIN")).contains(Permissions.USER_MANAGE);
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("AC-4: with a second role holding user.manage, the revocation is allowed")
    void secondHolderMakesItSafe() {
        store.grants.put("FM_ADMIN", new LinkedHashSet<>(Set.of(Permissions.USER_MANAGE)));
        store.userCounts.put("FM_ADMIN", 1);

        assertThatCode(() -> roles.replaceGrants(ACTOR, "SYSTEM_ADMIN",
                Set.of(Permissions.MASTERDATA_VIEW), versionOf("SYSTEM_ADMIN")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-4: a role holding user.manage with no active users is not cover")
    void roleWithNoUsersIsNotCover() {
        // The grant exists but nobody can exercise it, so it cannot be the reason to remove the
        // one that people actually hold.
        store.grants.put("FM_ADMIN", new LinkedHashSet<>(Set.of(Permissions.USER_MANAGE)));
        store.userCounts.put("FM_ADMIN", 0);

        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "SYSTEM_ADMIN",
                Set.of(Permissions.MASTERDATA_VIEW), versionOf("SYSTEM_ADMIN")))
                .isInstanceOf(RoleAdministration.AdministrativeLockout.class);
    }

    @Test
    @DisplayName("AC-4: when nobody holds user.manage already, the guard stands aside")
    void alreadyLockedOutDoesNotBlockRepair() {
        // Refusing here would mean an installation that has lost the permission can never restore
        // it — the guard would be preventing the repair rather than the damage.
        store.grants.put("SYSTEM_ADMIN", new LinkedHashSet<>(Set.of(Permissions.MASTERDATA_VIEW)));

        assertThatCode(() -> roles.replaceGrants(ACTOR, "SYSTEM_ADMIN",
                Set.of(Permissions.MASTERDATA_EDIT), versionOf("SYSTEM_ADMIN")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-4: the guard reads through the locking query, inside the transaction")
    void guardLocksInsideTheTransaction() {
        roles.replaceGrants(ACTOR, "TENANT", Set.of(Permissions.VISITOR_REQUEST),
                versionOf("TENANT"));

        assertThat(store.lockingReads).isEqualTo(1);
        assertThat(store.depthAtLock).isEqualTo(1);
    }

    // ---- scope limits ----

    @Test
    @DisplayName("an unknown permission code is refused rather than silently dropped")
    void unknownPermissionRefused() {
        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "TENANT",
                Set.of("visitor.reqest"), versionOf("TENANT")))
                .isInstanceOf(RoleAdministration.UnknownPermission.class);
    }

    @Test
    @DisplayName("an unbacked permission cannot be granted through the API either")
    void unbackedPermissionRefused() {
        // The migration withholds these because no requirement says when they are legitimate. An
        // API that granted them anyway would make that restraint decorative.
        for (String code : Permissions.UNBACKED) {
            assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "TENANT", Set.of(code),
                    versionOf("TENANT")))
                    .as("granting %s", code)
                    .isInstanceOf(RoleAdministration.PermissionNotGrantable.class);
        }
    }

    @Test
    @DisplayName("an unknown role is refused; creating one is not offered at all")
    void unknownRoleRefused() {
        assertThatThrownBy(() -> roles.replaceGrants(ACTOR, "INVENTED_ROLE", Set.of(), "v"))
                .isInstanceOf(RoleAdministration.UnknownRole.class);
        assertThatThrownBy(() -> roles.role("INVENTED_ROLE"))
                .isInstanceOf(RoleAdministration.UnknownRole.class);

        // Asserted structurally: there is nowhere for an endpoint to call.
        assertThat(java.util.Arrays.stream(RoleAdministration.class.getDeclaredMethods())
                .map(m -> m.getName().toLowerCase(java.util.Locale.ROOT)))
                .noneMatch(n -> n.contains("createrole") || n.contains("deleterole")
                        || n.contains("createpermission"));
    }

    // ---- fakes ----

    private final class DirectTransactions implements TransactionRunner {
        public <T> T call(Supplier<T> work) {
            store.depth++;
            try {
                return work.get();
            } finally {
                store.depth--;
            }
        }
    }

    private static final class FakeStore implements RoleGrantStore {
        final Map<String, Set<String>> grants = new LinkedHashMap<>(Map.of(
                "FM_ADMIN", new LinkedHashSet<>(Set.of(Permissions.VISITOR_APPROVE)),
                "SYSTEM_ADMIN", new LinkedHashSet<>(Set.of(Permissions.USER_MANAGE,
                        Permissions.MASTERDATA_EDIT)),
                "TENANT", new LinkedHashSet<>(Set.of(Permissions.VISITOR_REQUEST))));
        final Map<String, Integer> userCounts = new LinkedHashMap<>(Map.of(
                "FM_ADMIN", 1, "SYSTEM_ADMIN", 2, "TENANT", 5));
        int lockingReads;
        int depth;
        int depthAtLock;

        public List<Role> roles() {
            List<Role> out = new ArrayList<>();
            for (String code : new java.util.TreeSet<>(grants.keySet())) {
                out.add(new Role(UUID.randomUUID(), code, code, code + " description"));
            }
            return out;
        }
        public Optional<Role> roleByCode(String code) {
            return roles().stream().filter(r -> r.code().equals(code)).findFirst();
        }
        public Set<String> permissionCodes() { return Permissions.ALL; }
        public Set<String> grantsFor(String roleCode) {
            return grants.getOrDefault(roleCode, Set.of());
        }
        public Map<String, Set<String>> allGrants() { return new LinkedHashMap<>(grants); }
        public List<Permission> permissionCatalogue() {
            return Permissions.ALL.stream().map(c -> new Permission(c, c)).toList();
        }
        public Map<String, Integer> activeUserCountsByRole() { return userCounts; }
        public Map<String, Integer> lockActiveUserCountsByRole() {
            lockingReads++;
            depthAtLock = depth;
            return userCounts;
        }
        public void replaceGrants(String roleCode, Set<String> codes) {
            grants.put(roleCode, new LinkedHashSet<>(codes));
        }
        public Optional<String> activeUserRole(UUID userId) { return Optional.empty(); }
    }

    private static final class RecordingAudit implements AuditTrail {
        final List<Object[]> entries = new ArrayList<>();

        public void record(UUID a, String action, String t, String i, String d) {
            entries.add(new Object[]{a, action, t, i, null, null});
        }
        public void recordChange(UUID a, String action, String t, String i, String b, String af) {
            entries.add(new Object[]{a, action, t, i, b, af});
        }
        public void recordSecurityDenial(UUID a, String ac, String p, String r, String m, String o,
                                         String ip) {}

        List<String> actions() { return entries.stream().map(e -> (String) e[1]).toList(); }
    }
}
