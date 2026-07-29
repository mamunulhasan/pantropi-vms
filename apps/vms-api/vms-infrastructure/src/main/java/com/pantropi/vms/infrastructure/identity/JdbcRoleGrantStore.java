package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.RoleGrantStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC adapter for {@link RoleGrantStore} (US-03.1.1, T-03.1.1.1).
 *
 * <p>{@link #activeUserRole} filters on {@code is_active} in the query rather than reading the user
 * and checking afterwards — a deactivated user simply has no role to resolve, which is what makes
 * the empty permission set fall out naturally instead of needing a special case.
 */
public final class JdbcRoleGrantStore implements RoleGrantStore {

    private final JdbcTemplate jdbc;

    public JdbcRoleGrantStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Role> roles() {
        return jdbc.query("SELECT id, code, name, description FROM vms.roles ORDER BY code",
                (rs, i) -> new Role(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description")));
    }

    @Override
    public Optional<Role> roleByCode(String code) {
        return jdbc.query("SELECT id, code, name, description FROM vms.roles WHERE code = ?",
                (rs, i) -> new Role(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getString("description")), code)
                .stream().findFirst();
    }

    @Override
    public Set<String> permissionCodes() {
        return new HashSet<>(jdbc.queryForList("SELECT code FROM vms.permissions", String.class));
    }

    @Override
    public Set<String> grantsFor(String roleCode) {
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT p.code FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id
                JOIN vms.permissions p ON p.id = rp.permission_id
                WHERE r.code = ?
                ORDER BY p.code
                """, String.class, roleCode));
    }

    @Override
    public Map<String, Set<String>> allGrants() {
        // Every role appears, including those with no grants. A role missing from the map and a
        // role with an empty set read the same to a caller doing map.get(), and one of them is a
        // role that exists.
        Map<String, Set<String>> matrix = new LinkedHashMap<>();
        for (Role role : roles()) {
            matrix.put(role.code(), new LinkedHashSet<>());
        }
        jdbc.query("""
                SELECT r.code AS role_code, p.code AS permission_code
                FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id
                JOIN vms.permissions p ON p.id = rp.permission_id
                ORDER BY r.code, p.code
                """, rs -> {
            matrix.computeIfAbsent(rs.getString("role_code"), k -> new LinkedHashSet<>())
                    .add(rs.getString("permission_code"));
        });
        return matrix;
    }

    @Override
    public List<Permission> permissionCatalogue() {
        return jdbc.query("SELECT code, description FROM vms.permissions ORDER BY code",
                (rs, i) -> new Permission(rs.getString("code"), rs.getString("description")));
    }

    @Override
    public Map<String, Integer> activeUserCountsByRole() {
        return countsByRole();
    }

    @Override
    public Map<String, Integer> lockActiveUserCountsByRole() {
        // The lock is taken by a separate SELECT over the same rows: an aggregate cannot carry
        // FOR UPDATE, so counting and locking are two statements inside one transaction. The lock
        // is taken first, so the count that follows cannot be invalidated before the caller uses it.
        jdbc.query("""
                SELECT u.id FROM vms.users u WHERE u.is_active = true FOR UPDATE
                """, (rs, i) -> rs.getObject("id", UUID.class));
        return countsByRole();
    }

    private Map<String, Integer> countsByRole() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Role role : roles()) {
            counts.put(role.code(), 0);   // a role with no users is zero, not absent
        }
        jdbc.query("""
                SELECT r.code, count(u.id) AS active_users
                FROM vms.roles r
                LEFT JOIN vms.users u ON u.role_id = r.id AND u.is_active = true
                GROUP BY r.code
                """, rs -> {
            counts.put(rs.getString("code"), rs.getInt("active_users"));
        });
        return counts;
    }

    @Override
    public void replaceGrants(String roleCode, Set<String> permissionCodes) {
        UUID roleId = roleByCode(roleCode)
                .map(Role::id)
                .orElseThrow(() -> new IllegalArgumentException("No such role: " + roleCode));

        // Delete-then-insert inside the caller's transaction. Computing a minimal delta would be
        // the same number of statements and would have to be right about which rows changed; this
        // cannot be wrong about that.
        jdbc.update("DELETE FROM vms.role_permissions WHERE role_id = ?", roleId);

        for (String code : permissionCodes) {
            jdbc.update("""
                    INSERT INTO vms.role_permissions (role_id, permission_id)
                    SELECT ?, p.id FROM vms.permissions p WHERE p.code = ?
                    ON CONFLICT (role_id, permission_id) DO NOTHING
                    """, roleId, code);
        }
    }

    @Override
    public Optional<String> activeUserRole(UUID userId) {
        return jdbc.query("""
                SELECT r.code FROM vms.users u
                JOIN vms.roles r ON r.id = u.role_id
                WHERE u.id = ? AND u.is_active = true
                """, (rs, i) -> rs.getString("code"), userId).stream().findFirst();
    }
}
