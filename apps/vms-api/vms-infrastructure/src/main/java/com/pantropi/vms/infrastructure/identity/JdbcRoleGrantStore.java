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
    public Optional<String> activeUserRole(UUID userId) {
        return jdbc.query("""
                SELECT r.code FROM vms.users u
                JOIN vms.roles r ON r.id = u.role_id
                WHERE u.id = ? AND u.is_active = true
                """, (rs, i) -> rs.getString("code"), userId).stream().findFirst();
    }
}
