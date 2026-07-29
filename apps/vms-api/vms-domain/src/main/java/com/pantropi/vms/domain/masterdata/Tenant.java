package com.pantropi.vms.domain.masterdata;

import java.util.UUID;

/**
 * A tenant organisation in the building (US-04.3.1) — FR-CFG-04 (TDD-derived), schema
 * {@code vms.tenants}.
 *
 * <h2>The contact details are personal data</h2>
 * {@code contactEmail} and {@code contactPhone} identify a person at that organisation. They are
 * carried here because the schema has the columns and the register is useless without them, but they
 * must not reach an application log at any level (AC-6) — a log line is copied, shipped and retained
 * by things that were never assessed for holding personal data.
 *
 * <p>{@link #toString()} is overridden for that reason. A record's generated {@code toString}
 * prints every component, and one {@code log.debug("saving " + tenant)} anywhere would put both
 * values in a log file for as long as that file is kept. The audit trail records them, deliberately
 * and within a structured payload; nothing else does.
 *
 * <h2>The floor is optional</h2>
 * A tenant with no floor is legitimate — the schema makes {@code floor_id} nullable, and an
 * organisation may be registered before its space is assigned. When present it must reference an
 * <em>active</em> floor, which the adapter enforces as part of the write.
 *
 * <p>Pure Java: no framework.
 */
public record Tenant(UUID id, String code, String name, UUID floorId, String contactEmail,
                     String contactPhone, boolean active) {

    public Tenant {
        code = MasterDataText.code(code);
        name = MasterDataText.name(name);
        contactEmail = MasterDataText.email("contactEmail", contactEmail);
        contactPhone = MasterDataText.phone("contactPhone", contactPhone);
    }

    /** A tenant not yet persisted: no id, active from the moment it exists. */
    public static Tenant draft(String code, String name, UUID floorId, String contactEmail,
                               String contactPhone) {
        return new Tenant(null, code, name, floorId, contactEmail, contactPhone, true);
    }

    /**
     * Identifies the tenant without disclosing the contact details.
     *
     * <p>Overriding this is the single cheapest defence for AC-6: it means no accidental
     * interpolation of a Tenant into a log message, an exception message or a debugger-driven trace
     * can leak an address or a number, whatever future code does.
     */
    @Override
    public String toString() {
        return "Tenant[" + code + "]";
    }
}
