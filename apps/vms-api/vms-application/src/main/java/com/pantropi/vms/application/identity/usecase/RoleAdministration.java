package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.RoleGrantStore;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.domain.identity.GrantVersion;
import com.pantropi.vms.domain.identity.Permissions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * View roles and adjust what they grant (US-03.3.1) — FR-USR-02 (TDD-derived).
 *
 * <h2>Scope is deliberately narrow</h2>
 * Grants on the five seeded roles may be edited. Creating a role, deleting a role, and inventing a
 * permission code are <strong>not offered</strong>, because TODO-01 leaves FR-USR-02 undefined in the
 * SRS — the five roles and eleven permissions come from the published schema, and anything beyond
 * them would be invented. The methods to do it do not exist, so no endpoint can expose them.
 *
 * <h2>The lockout guard</h2>
 * Revoking {@code user.manage} from the last role that has an active user holding it would leave
 * nobody able to grant it back. That is a locked door with the key inside, and no amount of auditing
 * helps afterwards. The guard is evaluated against the state the change would produce, inside the
 * transaction that performs it, reading through a locking query — the same shape as the Master Admin
 * invariant, for the same reasons (AC-4).
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class RoleAdministration {

    private final RoleGrantStore store;
    private final TransactionRunner transactions;
    private final AuditTrail audit;

    public RoleAdministration(RoleGrantStore store, TransactionRunner transactions,
                              AuditTrail audit) {
        this.store = store;
        this.transactions = transactions;
        this.audit = audit;
    }

    /** Everything the administration screen needs in one read (AC-1). */
    public Overview overview() {
        Map<String, Set<String>> grants = store.allGrants();
        Map<String, Integer> userCounts = store.activeUserCountsByRole();

        List<RoleView> roles = new ArrayList<>();
        for (RoleGrantStore.Role role : store.roles()) {
            Set<String> codes = grants.getOrDefault(role.code(), Set.of());
            roles.add(new RoleView(role.code(), role.name(), role.description(),
                    new TreeSet<>(codes), GrantVersion.of(codes),
                    userCounts.getOrDefault(role.code(), 0)));
        }
        return new Overview(roles, store.permissionCatalogue());
    }

    /** One role's current grants and version, for a caller about to edit them. */
    public RoleView role(String roleCode) {
        return overview().roles().stream()
                .filter(r -> r.code().equals(roleCode))
                .findFirst()
                .orElseThrow(() -> new UnknownRole(roleCode));
    }

    /**
     * Set a role's grants to exactly this set.
     *
     * @param expectedVersion the version the caller was looking at (AC-5)
     * @throws UnknownRole        if the code is not one of the seeded roles
     * @throws UnknownPermission  if any requested code is not in the catalogue
     * @throws PermissionNotGrantable if a requested code has no requirement behind it yet
     * @throws StaleGrantVersion  if the grants changed since the caller read them
     * @throws AdministrativeLockout if the change would leave nobody able to manage users
     */
    public RoleView replaceGrants(UUID actorId, String roleCode, Set<String> requested,
                                  String expectedVersion) {
        Set<String> normalised = normalise(requested);

        return transactions.call(() -> {
            Map<String, Set<String>> current = store.allGrants();
            if (!current.containsKey(roleCode)) {
                throw new UnknownRole(roleCode);
            }
            Set<String> before = new TreeSet<>(current.getOrDefault(roleCode, Set.of()));

            if (!GrantVersion.matches(expectedVersion, before)) {
                // Carries the current state, so the second administrator can see what changed
                // instead of being told only that they lost.
                throw new StaleGrantVersion(roleCode, before, GrantVersion.of(before));
            }

            assertNoAdministrativeLockout(roleCode, normalised, current);

            store.replaceGrants(roleCode, normalised);

            audit.recordChange(actorId, "role.grants_changed", "role", roleCode,
                    json(before), json(normalised));

            return new RoleView(roleCode, null, null, new TreeSet<>(normalised),
                    GrantVersion.of(normalised),
                    store.activeUserCountsByRole().getOrDefault(roleCode, 0));
        });
    }

    /**
     * Refuse a change that would leave no active user holding {@code user.manage} (AC-4).
     *
     * <p>Evaluated against the resulting matrix, not by counting the current one — the lesson from
     * the Master Admin guard, where two rules that each counted correctly still let the invariant
     * break between them.
     *
     * <p>If nobody holds it already, the guard stands aside. Refusing then would mean an
     * installation that has somehow lost the permission can never restore it.
     */
    private void assertNoAdministrativeLockout(String roleCode, Set<String> proposed,
                                               Map<String, Set<String>> currentMatrix) {
        Map<String, Integer> activeUsers = store.lockActiveUserCountsByRole();

        if (!someoneCanManageUsers(currentMatrix, activeUsers)) {
            return;
        }

        Map<String, Set<String>> after = new java.util.LinkedHashMap<>(currentMatrix);
        after.put(roleCode, proposed);

        if (!someoneCanManageUsers(after, activeUsers)) {
            throw new AdministrativeLockout(roleCode);
        }
    }

    private static boolean someoneCanManageUsers(Map<String, Set<String>> matrix,
                                                 Map<String, Integer> activeUsers) {
        return matrix.entrySet().stream()
                .filter(e -> e.getValue().contains(Permissions.USER_MANAGE))
                .anyMatch(e -> activeUsers.getOrDefault(e.getKey(), 0) > 0);
    }

    /** Lower-cases, de-duplicates, and refuses anything the catalogue does not contain. */
    private static Set<String> normalise(Set<String> requested) {
        Set<String> normalised = new LinkedHashSet<>();
        if (requested == null) {
            return normalised;
        }
        for (String raw : requested) {
            String code = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
            if (!Permissions.isKnown(code)) {
                throw new UnknownPermission(code);
            }
            if (Permissions.UNBACKED.contains(code)) {
                // Enforced here as well as in the migration. The matrix withholds these because no
                // requirement says when they are legitimate (TODO-04, TODO-16); an API that granted
                // them anyway would make the migration's restraint decorative.
                throw new PermissionNotGrantable(code);
            }
            normalised.add(code);
        }
        return normalised;
    }

    private static String json(Set<String> codes) {
        return "{\"permissions\":[" + String.join(",",
                new TreeSet<>(codes).stream().map(c -> "\"" + c + "\"").toList()) + "]}";
    }

    /**
     * @param version the token to send back when changing these grants
     * @param activeUsers how many active users hold this role — what an administrator needs to see
     *                    before deciding a revocation is safe
     */
    public record RoleView(String code, String name, String description, Set<String> permissions,
                           String version, int activeUsers) {}

    public record Overview(List<RoleView> roles, List<RoleGrantStore.Permission> catalogue) {}

    public static final class UnknownRole extends RuntimeException {
        public UnknownRole(String code) {
            super("No such role: " + code
                    + ". Roles are the five seeded by the schema; creating roles is not offered.");
        }
    }

    public static final class UnknownPermission extends RuntimeException {
        public final String code;

        public UnknownPermission(String code) {
            super("No such permission: " + code
                    + ". Permissions are the eleven seeded by the schema; inventing one is not "
                    + "offered while TODO-01 leaves FR-USR-02 undefined.");
            this.code = code;
        }
    }

    public static final class PermissionNotGrantable extends RuntimeException {
        public final String code;

        public PermissionNotGrantable(String code) {
            super(code + " cannot be granted yet: no requirement defines when it is legitimate "
                    + "(see TODO-04 and TODO-16). It is deliberately held by no role.");
            this.code = code;
        }
    }

    /** Carries the current state so the loser of a race can see it rather than just be refused. */
    public static final class StaleGrantVersion extends RuntimeException {
        public final String roleCode;
        public final Set<String> currentPermissions;
        public final String currentVersion;

        public StaleGrantVersion(String roleCode, Set<String> current, String currentVersion) {
            super("The grants for " + roleCode + " changed since you loaded them.");
            this.roleCode = roleCode;
            this.currentPermissions = current;
            this.currentVersion = currentVersion;
        }
    }

    public static final class AdministrativeLockout extends RuntimeException {
        public AdministrativeLockout(String roleCode) {
            super("Refused: removing user.manage from " + roleCode + " would leave no active user "
                    + "able to manage users or grants — including the ability to undo this. Grant "
                    + "user.manage to another role with active users first.");
        }
    }
}
