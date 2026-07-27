package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.PermissionChecker;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JDBC adapter for {@link PermissionChecker} over {@code vms.role_permissions} (US-02.2.1).
 */
public final class JdbcPermissionChecker implements PermissionChecker {

    private final JdbcTemplate jdbc;

    public JdbcPermissionChecker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean roleHasPermission(String roleCode, String permissionCode) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id
                JOIN vms.permissions p ON p.id = rp.permission_id
                WHERE r.code = ? AND p.code = ?
                """, Long.class, roleCode, permissionCode);
        return n != null && n > 0;
    }
}
