package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AdminDirectory;

import java.util.UUID;

/**
 * Protects the FR-ADM-01 (SRS B1) authority (US-02.4.1, T-02.4.1.1/2).
 *
 * <p>Two invariants:
 * <ul>
 *   <li><b>AC-3</b> — a Master Admin may only be assigned a <i>central</i> reception
 *       ({@code vms.receptions.is_central = true}); FR-ADM-01 places the authority "at central
 *       reception".</li>
 *   <li><b>AC-4</b> — the last active Master Admin cannot be deactivated or downgraded, or the
 *       system would hold no FR-ADM-01 authority.</li>
 * </ul>
 *
 * <p>The last-admin guard must be invoked inside a serialisable transaction by the adapter so two
 * concurrent deactivations cannot both observe a count of 2 and both proceed (AC-4). This class
 * expresses the rule; the transaction boundary is an infrastructure concern.
 */
public final class MasterAdminPolicy {

    public static final String ROLE = "MASTER_ADMIN";

    private final AdminDirectory admin;

    public MasterAdminPolicy(AdminDirectory admin) {
        this.admin = admin;
    }

    /** @throws ReceptionNotCentral if the reception is missing or not flagged central (AC-3) */
    public void assertReceptionAssignable(UUID receptionId) {
        if (receptionId == null || !admin.receptionIsCentral(receptionId)) {
            throw new ReceptionNotCentral(receptionId);
        }
    }

    /** @throws LastMasterAdmin if removing this authority would leave none active (AC-4) */
    public void assertCanReleaseMasterAdmin() {
        if (admin.countActiveUsersWithRole(ROLE) <= 1) {
            throw new LastMasterAdmin();
        }
    }

    public static final class ReceptionNotCentral extends RuntimeException {
        public ReceptionNotCentral(UUID receptionId) {
            super("A Master Admin must be assigned a central reception; " + receptionId
                    + " is not flagged is_central (FR-ADM-01, SRS B1)");
        }
    }

    public static final class LastMasterAdmin extends RuntimeException {
        public LastMasterAdmin() {
            super("Refused: this is the only active Master Admin; the system would be left with no "
                    + "holder of FR-ADM-01 authority");
        }
    }
}
