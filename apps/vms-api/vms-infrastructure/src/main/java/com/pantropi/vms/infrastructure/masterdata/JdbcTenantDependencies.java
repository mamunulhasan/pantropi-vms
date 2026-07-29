package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.TenantDependencies;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Counts what still depends on a tenant (US-04.3.1, T-04.3.1.2, AC-3).
 *
 * <p>Its own class rather than another method on {@link JdbcTenantStore}: this reads
 * {@code vms.users}, a different table belonging to a different context. Keeping it separate also
 * avoids one adapter implementing two ports, which made every bean of it a candidate for both and
 * left Spring unable to tell them apart.
 */
public final class JdbcTenantDependencies implements TenantDependencies {

    private final JdbcTemplate jdbc;

    public JdbcTenantDependencies(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int activeUserCount(UUID tenantId) {
        // One statement, so the number is consistent at the moment it is read. It is a snapshot for
        // an administrator to look at, not a gate — a user can be assigned a millisecond later.
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM vms.users WHERE tenant_id = ? AND is_active",
                Integer.class, tenantId);
        return count == null ? 0 : count;
    }
}
