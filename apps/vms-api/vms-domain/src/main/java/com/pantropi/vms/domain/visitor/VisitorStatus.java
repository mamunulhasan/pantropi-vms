package com.pantropi.vms.domain.visitor;

/**
 * Lifecycle of an individual visitor (US-07.1.1) — mirrors {@code vms.visitor_status}.
 * FR-VMS-03 (SRS B1); later states are driven by the entry lifecycle in Phase 3.
 */
public enum VisitorStatus {
    PENDING,
    APPROVED,
    CHECKED_IN,
    INSIDE,
    CHECKED_OUT,
    EXPIRED,
    CANCELLED,
    NO_SHOW;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static VisitorStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
