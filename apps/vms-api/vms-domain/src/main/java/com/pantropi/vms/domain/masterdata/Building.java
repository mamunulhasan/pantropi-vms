package com.pantropi.vms.domain.masterdata;

import java.util.UUID;

/**
 * A building in the register (US-04.1.1) — FR-CFG-02 (TDD-derived), schema {@code vms.buildings}.
 *
 * <p>Validated on construction, so an invalid building cannot exist as an object — there is no
 * setter and no partially-built state to check later.
 *
 * <p><strong>There is no delete.</strong> Not "delete is guarded", not "delete needs a confirmation"
 * — the operation does not exist anywhere in this context. {@code vms.floors.building_id} is
 * {@code ON DELETE RESTRICT}, and a visitor record from two years ago must still resolve the
 * building it names. Deactivation is the whole vocabulary (AC-3, AC-5).
 *
 * <p>Pure Java: no framework.
 */
public record Building(UUID id, String code, String name, String address, boolean active) {

    private static final int ADDRESS_MAX = 500;

    public Building {
        code = MasterDataText.code(code);
        name = MasterDataText.name(name);
        address = MasterDataText.optionalText("address", address, ADDRESS_MAX);
    }

    /** A building not yet persisted: no id, and active from the moment it exists (AC-1). */
    public static Building draft(String code, String name, String address) {
        return new Building(null, code, name, address, true);
    }
}
