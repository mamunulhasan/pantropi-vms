package com.pantropi.vms.application.masterdata;

import java.util.UUID;

/**
 * What {@link com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration} needs to
 * know about one entity in order to administer it generically (US-04.1.1, T-04.1.1.1).
 *
 * <p>This is the seam that lets the create/update/deactivate/list logic be written once. Everything
 * genuinely entity-specific — the columns, the audit rendering, the identity of the type — is
 * answered here; nothing entity-specific leaks into the shared use case.
 *
 * <p>An implementation is a small, dependency-free adapter between a domain record and the pattern.
 */
public interface MasterDataDefinition<T> {

    /** Audit {@code entity_type}, e.g. {@code building}. Also used in "not found" messages. */
    String entityType();

    /** The code carried by this record — the field the uniqueness constraint governs. */
    String code(T record);

    /** Whether this record is currently active, so the use case can reject no-op state changes. */
    boolean active(T record);

    /** The same record with an id and active flag applied, for rendering an "after" state. */
    T withState(T record, UUID id, boolean active);

    /**
     * Before/after state for the audit trail.
     *
     * <p>The implementation decides what may be recorded. That matters for entities carrying
     * contact details — a tenant's email is personal data and must not land in {@code audit_logs}
     * merely because the row changed.
     */
    String auditJson(T record);
}
