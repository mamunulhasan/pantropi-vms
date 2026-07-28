package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.domain.masterdata.Building;

import java.util.UUID;

/**
 * Teaches the master data pattern about {@link Building} (US-04.1.1).
 *
 * <p>The whole of what is building-specific about administering buildings. Everything else — the
 * create/update/deactivate flow, the audit rows, the absence of delete — comes from
 * {@link com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration}.
 */
public final class BuildingDefinition implements MasterDataDefinition<Building> {

    @Override
    public String entityType() {
        return "building";
    }

    @Override
    public String code(Building record) {
        return record.code();
    }

    @Override
    public boolean active(Building record) {
        return record.active();
    }

    @Override
    public Building withState(Building record, UUID id, boolean active) {
        return new Building(id, record.code(), record.name(), record.address(), active);
    }

    @Override
    public String auditJson(Building record) {
        // A building's address is the postal address of a commercial tower, not personal data, so
        // it is recorded. Entities whose fields are personal (a tenant's contact email) will omit
        // them here — which is exactly why this decision belongs per entity rather than in the
        // shared use case.
        return "{\"code\":\"" + escape(record.code())
                + "\",\"name\":\"" + escape(record.name())
                + "\",\"address\":" + quotedOrNull(record.address())
                + ",\"active\":" + record.active() + "}";
    }

    private static String quotedOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
