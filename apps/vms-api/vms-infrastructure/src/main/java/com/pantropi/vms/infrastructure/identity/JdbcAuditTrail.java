package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AuditTrail;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * JDBC adapter for {@link AuditTrail} over the append-only {@code vms.audit_logs} (US-02.1.2 /
 * US-02.2.1 / US-03.2.2). Records identifiers, the action and before/after state only — never a
 * token, a password, a password hash, or request/response body content.
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
                detail == null ? null : "{\"detail\":\"" + safe(detail) + "\"}");
    }

    @Override
    public void recordChange(UUID actorId, String action, String entityType, String entityId,
                             String beforeJson, String afterJson) {
        jdbc.update("""
                INSERT INTO vms.audit_logs (user_id, action, entity_type, entity_id, before_state, after_state)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, actorId, action, entityType, entityId, beforeJson, afterJson);
    }

    @Override
    public void recordSecurityDenial(UUID actorId, String action, String attemptedPermission,
                                     String route, String method, String outcome, String sourceIp) {
        // after_state carries only non-personal request metadata: never a body or query string.
        String state = "{"
                + "\"permission\":\"" + safe(attemptedPermission) + "\","
                + "\"route\":\"" + safe(route) + "\","
                + "\"method\":\"" + safe(method) + "\","
                + "\"outcome\":\"" + safe(outcome) + "\","
                + "\"actor\":\"" + (actorId == null ? "anonymous" : actorId.toString()) + "\""
                + "}";
        jdbc.update("""
                INSERT INTO vms.audit_logs (user_id, action, entity_type, entity_id, after_state, ip_address)
                VALUES (?, ?, 'security', ?, ?::jsonb, ?::inet)
                """, actorId, action, safe(route), state, sourceIp);
    }

    /**
     * Defensive escaping: values derived from a request must never break out of the JSON literal.
     * Quotes and control characters are neutralised rather than escaped, because nothing written
     * here legitimately contains them.
     */
    private static String safe(String value) {
        if (value == null) {
            return "-";
        }
        return value.replace("\\", "")
                .replace("\"", "'")
                .replace("\n", " ")
                .replace("\r", " ");
    }
}
