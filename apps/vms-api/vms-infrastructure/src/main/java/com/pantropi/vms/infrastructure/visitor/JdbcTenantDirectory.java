package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.visitor.port.TenantDirectory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/** JDBC adapter for {@link TenantDirectory} (US-07.1.1). Parameterised queries only. */
public final class JdbcTenantDirectory implements TenantDirectory {

    private final JdbcTemplate jdbc;

    public JdbcTenantDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> tenantOfUser(UUID userId) {
        return jdbc.query("SELECT tenant_id FROM vms.users WHERE id = ? AND tenant_id IS NOT NULL",
                (rs, i) -> rs.getObject("tenant_id", UUID.class), userId).stream().findFirst();
    }

    @Override
    public boolean hostBelongsToTenant(UUID hostId, UUID tenantId) {
        Long n = jdbc.queryForObject("""
                SELECT count(*) FROM vms.hosts
                WHERE id = ? AND tenant_id = ? AND is_active = true
                """, Long.class, hostId, tenantId);
        return n != null && n > 0;
    }
}
