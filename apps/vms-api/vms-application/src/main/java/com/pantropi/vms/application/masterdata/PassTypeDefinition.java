package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.domain.masterdata.PassType;

import java.util.UUID;

/**
 * Teaches the master data pattern about {@link PassType} (US-04.6.1).
 *
 * <p>The audit rendering carries <strong>every</strong> field, not a summary. AC-4 exists because
 * these values decide what is requested from the access control system: reconstructing why a
 * credential issued in March had the validity window it had means reading the pass type as it was
 * in March, and a partial audit record cannot answer that.
 */
public final class PassTypeDefinition implements MasterDataDefinition<PassType> {

    @Override
    public String entityType() {
        return "pass_type";
    }

    @Override
    public String code(PassType record) {
        return record.code();
    }

    @Override
    public boolean active(PassType record) {
        return record.active();
    }

    @Override
    public PassType withState(PassType record, UUID id, boolean active) {
        return new PassType(id, record.code(), record.name(), record.defaultCredential(),
                record.defaultRestriction(), record.defaultValidHours(), active);
    }

    @Override
    public String auditJson(PassType record) {
        return "{\"code\":\"" + escape(record.code())
                + "\",\"name\":\"" + escape(record.name())
                + "\",\"defaultCredential\":\"" + record.defaultCredential().databaseValue()
                + "\",\"defaultRestriction\":\"" + record.defaultRestriction().databaseValue()
                + "\",\"defaultValidHours\":" + record.defaultValidHours()
                + ",\"active\":" + record.active() + "}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
