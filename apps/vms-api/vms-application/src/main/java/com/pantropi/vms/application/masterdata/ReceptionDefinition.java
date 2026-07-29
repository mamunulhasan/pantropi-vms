package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.domain.masterdata.Reception;

import java.util.UUID;

/** Teaches the master data pattern about {@link Reception} (US-04.4.1). */
public final class ReceptionDefinition implements MasterDataDefinition<Reception> {

    @Override
    public String entityType() {
        return "reception";
    }

    @Override
    public String code(Reception record) {
        return record.code();
    }

    @Override
    public boolean active(Reception record) {
        return record.active();
    }

    @Override
    public Reception withState(Reception record, UUID id, boolean active) {
        return new Reception(id, record.floorId(), record.code(), record.name(), record.central(),
                active);
    }

    @Override
    public String auditJson(Reception record) {
        // The central flag is recorded on every change, not only on a transfer: reconstructing who
        // held the designation on a given date is the point of auditing it at all.
        return "{\"floorId\":\"" + record.floorId()
                + "\",\"code\":\"" + escape(record.code())
                + "\",\"name\":\"" + escape(record.name())
                + "\",\"central\":" + record.central()
                + ",\"active\":" + record.active() + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
