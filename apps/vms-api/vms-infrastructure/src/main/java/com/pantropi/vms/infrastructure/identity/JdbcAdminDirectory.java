package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AdminDirectory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link AdminDirectory} (US-02.4.1). Parameterised queries only.
 */
public final class JdbcAdminDirectory implements AdminDirectory {

    private final JdbcTemplate jdbc;

    public JdbcAdminDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countUsers() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM vms.users", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public long countActiveUsersWithRole(String roleCode) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM vms.users u
                JOIN vms.roles r ON r.id = u.role_id
                WHERE r.code = ? AND u.is_active = true
                """, Long.class, roleCode);
        return n == null ? 0 : n;
    }

    @Override
    public Optional<UUID> roleIdByCode(String roleCode) {
        return jdbc.query("SELECT id FROM vms.roles WHERE code = ?",
                (rs, i) -> rs.getObject("id", UUID.class), roleCode).stream().findFirst();
    }

    @Override
    public boolean receptionIsCentral(UUID receptionId) {
        Boolean central = jdbc.query(
                "SELECT is_central FROM vms.receptions WHERE id = ?",
                (rs, i) -> rs.getBoolean("is_central"), receptionId).stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(central);
    }

    @Override
    public void insertUser(UUID id, String username, String passwordHash, UUID roleId,
                           UUID receptionId, boolean active) {
        jdbc.update("""
                INSERT INTO vms.users (id, username, full_name, password_hash, role_id, reception_id, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, username, username, passwordHash, roleId, receptionId, active);
    }
}
