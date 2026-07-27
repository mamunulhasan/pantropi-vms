package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AuditTrail;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * JDBC adapter for {@link AuditTrail} over the append-only {@code vms.audit_logs} (US-02.1.2 /
 * US-02.2.1). Records identifiers, the action and before/after state only — never a token, a
 * password, or a password hash (the caller excludes those).
 */
public final class JdbcAuditTrail implements AuditTrail {

    private final JdbcTemplate jdbc;

    public JdbcAuditTrail(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID actorId, String action, String entityType, String entityId, String detail) {
        jdbc.update("""
                INSERT INTO vms.audit_logs (user_id, action, entity_type, entity_id, after_state)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, actorId, action, entityType, entityId,
                detail == null ? null : "{\"detail\":\"" + detail.replace("\"", "'") + "\"}");
    }

    @Override
    public void recordChange(UUID actorId, String action, String entityType, String entityId,
                             String beforeJson, String afterJson) {
        jdbc.update("""
                INSERT INTO vms.audit_logs (user_id, action, entity_type, entity_id, before_state, after_state)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, actorId, action, entityType, entityId, beforeJson, afterJson);
    }
}
