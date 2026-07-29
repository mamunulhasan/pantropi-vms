package com.pantropi.vms.domain.masterdata;

import java.util.UUID;

/**
 * A reception point on a floor (US-04.4.1) — FR-CFG-05 (TDD-derived), schema
 * {@code vms.receptions}. Consumed by FR-ADM-01 (SRS B1) and FR-ADM-02 (SRS B1): the 120 floor
 * receptions plus one central reception.
 *
 * <p>Codes are unique within a floor, not globally — every floor may have a "RC01" (AC-4).
 *
 * <h2>{@code central} is a designation, not an attribute</h2>
 * FR-ADM-01 places the Master Admin authority "at central reception", singular. So setting it on one
 * reception necessarily clears it from whichever held it, and that transfer has to be one atomic
 * operation: applied halfway, the floor has either two central receptions or none, and both are
 * states the Master Admin rules do not expect. The transfer therefore lives in a use case, not in a
 * setter here.
 *
 * <p>Nothing in the schema enforces the singularity — there is no partial unique index on
 * {@code is_central}. That is worth knowing rather than assuming: the application is the only thing
 * keeping it true, so the operation that changes it is the one that has to be careful.
 *
 * <p>Pure Java: no framework.
 */
public record Reception(UUID id, UUID floorId, String code, String name, boolean central,
                        boolean active) {

    public Reception {
        if (floorId == null) {
            throw new MasterDataText.InvalidField("floorId", "is required");
        }
        code = MasterDataText.code(code);
        name = MasterDataText.name(name);
    }

    /** A reception not yet persisted: no id, active from the moment it exists. */
    public static Reception draft(UUID floorId, String code, String name, boolean central) {
        return new Reception(null, floorId, code, name, central, true);
    }
}
