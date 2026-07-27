package com.pantropi.vms.application.identity.port;

import java.util.UUID;

/**
 * Outbound port for security audit events (US-02.1.2, T-02.1.2.3).
 *
 * <p>Writes to the append-only {@code vms.audit_logs}. This is targeted security-event logging
 * justified by NFR-SEC-01 (SRS B1); the full audit framework (F-05.1) is a later, broader story.
 * Never record a token value or password — only identifiers and the action.
 */
public interface AuditTrail {

    void record(UUID userId, String action, String entityType, String entityId, String detail);
}
