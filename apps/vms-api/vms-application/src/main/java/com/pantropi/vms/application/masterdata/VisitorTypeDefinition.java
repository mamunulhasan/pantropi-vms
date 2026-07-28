package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.domain.masterdata.VisitorType;

import java.util.UUID;

/**
 * Teaches the master data pattern about {@link VisitorType} (US-04.5.1).
 *
 * <p>The plainest entity yet — no parent, no extra columns beyond a description — and so a fair
 * measure of what the pattern costs for a simple case: this file and a row mapper.
 */
public final class VisitorTypeDefinition implements MasterDataDefinition<VisitorType> {

    @Override
    public String entityType() {
        return "visitor_type";
    }

    @Override
    public String code(VisitorType record) {
        return record.code();
    }

    @Override
    public boolean active(VisitorType record) {
        return record.active();
    }

    @Override
    public VisitorType withState(VisitorType record, UUID id, boolean active) {
        return new VisitorType(id, record.code(), record.name(), record.description(), active);
    }

    @Override
    public String auditJson(VisitorType record) {
        // Nothing here is personal data: this describes a category, not a person.
        return "{\"code\":\"" + escape(record.code())
                + "\",\"name\":\"" + escape(record.name())
                + "\",\"description\":" + quotedOrNull(record.description())
                + ",\"active\":" + record.active() + "}";
    }

    private static String quotedOrNull(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
