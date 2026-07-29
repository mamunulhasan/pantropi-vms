package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.domain.masterdata.Tenant;

import java.util.UUID;

/**
 * Teaches the master data pattern about {@link Tenant} (US-04.3.1).
 *
 * <p>This is the entity that justified putting {@code auditJson} on the definition rather than in
 * the shared use case. A building's address is a commercial postal address; a tenant's contact email
 * and phone are <strong>personal data</strong>, and whether they belong in an audit payload is a
 * decision only this entity can make.
 *
 * <p>They are included, and that is deliberate: AC-6 permits the audit to hold them inside the
 * structured before/after payload, and an audit of a tenant change that omitted the field that
 * changed would not be an audit. What AC-6 forbids is those values reaching an application
 * <em>log</em> — a different destination with different retention and different readers.
 */
public final class TenantDefinition implements MasterDataDefinition<Tenant> {

    @Override
    public String entityType() {
        return "tenant";
    }

    @Override
    public String code(Tenant record) {
        return record.code();
    }

    @Override
    public boolean active(Tenant record) {
        return record.active();
    }

    @Override
    public Tenant withState(Tenant record, UUID id, boolean active) {
        return new Tenant(id, record.code(), record.name(), record.floorId(),
                record.contactEmail(), record.contactPhone(), active);
    }

    @Override
    public String auditJson(Tenant record) {
        return "{\"code\":\"" + escape(record.code())
                + "\",\"name\":\"" + escape(record.name())
                + "\",\"floorId\":" + quotedOrNull(record.floorId() == null
                        ? null : record.floorId().toString())
                + ",\"contactEmail\":" + quotedOrNull(record.contactEmail())
                + ",\"contactPhone\":" + quotedOrNull(record.contactPhone())
                + ",\"active\":" + record.active() + "}";
    }

    private static String quotedOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
