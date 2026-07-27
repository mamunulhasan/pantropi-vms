package com.pantropi.vms.domain.visitor;

/**
 * Lifecycle of a visitor request (US-07.1.1) — mirrors the PostgreSQL enum
 * {@code vms.request_status} exactly. FR-VMS-01, FR-VMS-02 (SRS B1).
 */
public enum RequestStatus {
    SUBMITTED,
    APPROVED,
    REJECTED,
    CANCELLED;

    /** Wire form used by the database enum. */
    public String dbValue() {
        return name().toLowerCase();
    }

    public static RequestStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
