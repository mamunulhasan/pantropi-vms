package com.pantropi.vms.domain.visitor;

/**
 * Whether a visit was arranged ahead or walked in (US-07.1.1) — mirrors
 * {@code vms.visit_kind}. FR-VMS-01 (pre-scheduled), FR-VMS-08 (walk-in), SRS B1.
 */
public enum VisitKind {
    PRE_SCHEDULED,
    WALK_IN;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static VisitKind fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
