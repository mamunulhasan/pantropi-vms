package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.domain.masterdata.Floor;

import java.util.UUID;

/**
 * Teaches the master data pattern about {@link Floor} (US-04.2.1).
 *
 * <p>The second entity on the pattern, and the first with a parent. Nothing about having a parent
 * needed the pattern to change here: the building id travels inside the record, so
 * {@link com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration} never learns that
 * floors are nested. Only the port gained a parent filter and a parent-validity failure, both of
 * which the next child entity reuses.
 */
public final class FloorDefinition implements MasterDataDefinition<Floor> {

    @Override
    public String entityType() {
        return "floor";
    }

    @Override
    public String code(Floor record) {
        return record.code();
    }

    @Override
    public boolean active(Floor record) {
        return record.active();
    }

    @Override
    public Floor withState(Floor record, UUID id, boolean active) {
        return new Floor(id, record.buildingId(), record.code(), record.name(), record.levelNo(),
                active);
    }

    @Override
    public String auditJson(Floor record) {
        // The building id is recorded: a floor's identity is meaningless without it, since codes
        // repeat across buildings. Nothing here is personal data.
        return "{\"buildingId\":\"" + record.buildingId()
                + "\",\"code\":\"" + escape(record.code())
                + "\",\"name\":\"" + escape(record.name())
                + "\",\"levelNo\":" + (record.levelNo() == null ? "null" : record.levelNo())
                + ",\"active\":" + record.active() + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
