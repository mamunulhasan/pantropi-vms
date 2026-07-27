package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AuditTrail;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * JDBC adapter for {@link AuditTrail} over the append-only {@code vms.audit_logs} (US-02.1.2).
 * Records identifiers and the action only — never a token or password.
 */
public final class JdbcAuditTrail implements AuditTrail {

    private final JdbcTemplate jdbc;

    public JdbcAuditTrail(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID userId, String action, String entityType, String entityId, String detail) {
        jdbc.update("""
                INSERT INTO vms.audit_logs (user_id, action, entity_type, entity_id, after_state)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, userId, action, entityType, entityId,
                detail == null ? null : "{\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }
}
