package com.pantropi.vms.application.identity.port;

import java.util.UUID;

/**
 * Outbound port for security audit events (US-02.1.2 / US-02.2.1).
 *
 * <p>Writes to the append-only {@code vms.audit_logs}. Targeted security-event logging justified
 * by NFR-SEC-01 (SRS B1); the full audit framework (F-05.1) is a later, broader story. Never
 * record a token value, a password, or a password hash — only identifiers, the action, and
 * before/after state with sensitive fields excluded.
 */
public interface AuditTrail {

    /** Simple event: actor, action, target, optional free-text detail. */
    void record(UUID actorId, String action, String entityType, String entityId, String detail);

    /** State change: before and after JSON, with password material excluded by the caller. */
    void recordChange(UUID actorId, String action, String entityType, String entityId,
                      String beforeJson, String afterJson);

    /**
     * Security denial (US-03.2.2, T-03.2.2.2). Records who (null = anonymous), what was attempted,
     * where and from which address — and never any request or response body content.
     */
    void recordSecurityDenial(UUID actorId, String action, String attemptedPermission,
                              String route, String method, String outcome, String sourceIp);
}
