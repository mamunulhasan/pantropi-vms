package com.pantropi.vms.domain.masterdata;

import java.util.UUID;

/**
 * How a visitor is classified (US-04.5.1) — FR-CFG-06 (TDD-derived), schema
 * {@code vms.visitor_types}. Seeded with {@code GUEST}, {@code CONTRACTOR}, {@code VIP} and
 * {@code INTERVIEW}.
 *
 * <p><strong>There is no delete, and here the reason is not a foreign key.</strong>
 * {@code vms.visitors.visitor_type_id} is {@code ON DELETE SET NULL}, so the database would happily
 * allow it — a deletion would succeed and quietly blank the classification of every visitor ever
 * recorded under that type. The history would still be there, just no longer saying anything. That
 * is worse than a rejected delete, so retirement is deactivation (AC-5).
 *
 * <p>Pure Java: no framework.
 */
public record VisitorType(UUID id, String code, String name, String description, boolean active) {

    private static final int DESCRIPTION_MAX = 500;

    public VisitorType {
        code = MasterDataText.code(code);
        name = MasterDataText.name(name);
        description = MasterDataText.optionalText("description", description, DESCRIPTION_MAX);
    }

    /** A visitor type not yet persisted: no id, active from the moment it exists. */
    public static VisitorType draft(String code, String name, String description) {
        return new VisitorType(null, code, name, description, true);
    }
}
