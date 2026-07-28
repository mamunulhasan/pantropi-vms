package com.pantropi.vms.domain.masterdata;

import java.util.UUID;

/**
 * A floor within a building (US-04.2.1) — FR-CFG-03 (TDD-derived), schema {@code vms.floors}.
 *
 * <p>The {@code code} is unique <strong>within its building</strong>, not globally: every tower has
 * a "L01", and demanding they be distinct across the estate would be an invented rule the schema's
 * {@code UNIQUE (building_id, code)} does not ask for (AC-4).
 *
 * <p>{@code levelNo} is optional because floors are not always numbered — a mezzanine, a basement, a
 * roof plant room. It orders the listing where present (AC-2), so it is a display concern rather
 * than an identifier, and nothing keys off it.
 *
 * <p>As with {@link Building}, <strong>there is no delete</strong>: {@code vms.receptions.floor_id}
 * is {@code ON DELETE RESTRICT} (AC-6).
 *
 * <p>Pure Java: no framework.
 */
public record Floor(UUID id, UUID buildingId, String code, String name, Integer levelNo,
                    boolean active) {

    public Floor {
        if (buildingId == null) {
            throw new MasterDataText.InvalidField("buildingId", "is required");
        }
        code = MasterDataText.code(code);
        name = MasterDataText.name(name);
        // A level number is a storey, not an arbitrary integer. Bounds keep an obvious typo — a
        // year pasted into the field — from becoming a floor sorted somewhere absurd.
        if (levelNo != null && (levelNo < -20 || levelNo > 200)) {
            throw new MasterDataText.InvalidField("levelNo", "must be between -20 and 200");
        }
    }

    /** A floor not yet persisted: no id, active from the moment it exists. */
    public static Floor draft(UUID buildingId, String code, String name, Integer levelNo) {
        return new Floor(null, buildingId, code, name, levelNo, true);
    }
}
