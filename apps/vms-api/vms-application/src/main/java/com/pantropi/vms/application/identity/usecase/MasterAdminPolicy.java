package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AdminDirectory;

import java.util.List;
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

    /**
     * The invariant both locking guards below protect (US-04.4.1, T-04.4.1.3).
     *
     * <p>Not "at least one Master Admin exists" and not "the central reception is active", but the
     * conjunction: <strong>at least one active Master Admin is stationed at an active central
     * reception</strong>. FR-ADM-01 (SRS B1) places the authority at central reception, so an
     * administrator whose reception has been retired holds nothing exercisable.
     *
     * <p>Counting holders — the obvious reading of the two acceptance criteria — does not compose.
     * With two administrators, retiring the central reception passes a count check, and then
     * deactivating the other administrator passes a count check too; the survivor is left stationed
     * nowhere. Each operation is individually innocent and the pair is not. Asking instead whether
     * the invariant <em>still holds afterwards</em> closes that, in either order and under
     * concurrency.
     */
    private static boolean satisfied(List<AdminDirectory.RoleHolder> holders) {
        return holders.stream().anyMatch(AdminDirectory.RoleHolder::stationValid);
    }

    /**
     * Refuse a user deactivation that would leave the authority unexercisable (US-02.4.1 AC-4,
     * strengthened by US-04.4.1).
     *
     * <p>If the invariant is <em>already</em> broken this permits the operation. Refusing then would
     * mean an installation that has somehow lost its central reception could no longer change any
     * administrator — a policy that locks the door on the people coming to fix it.
     *
     * @throws LastMasterAdmin if this deactivation is what breaks it
     */
    public void assertUserDeactivatable(UUID userId) {
        List<AdminDirectory.RoleHolder> holders = admin.lockActiveHoldersOf(ROLE);
        if (!satisfied(holders)) {
            return;
        }
        List<AdminDirectory.RoleHolder> remaining = holders.stream()
                .filter(h -> !h.userId().equals(userId))
                .toList();
        if (!satisfied(remaining)) {
            throw new LastMasterAdmin();
        }
    }

    /**
     * Refuse a reception deactivation that would strand the authority (US-04.4.1 AC-6).
     *
     * <p>Reads through the same locked set as {@link #assertUserDeactivatable}, which is the point:
     * the two operations contend on the same rows, so neither can slip between the other's check and
     * its write.
     *
     * @throws WouldStrandMasterAdmin if this deactivation is what breaks the invariant
     */
    public void assertReceptionDeactivatable(UUID receptionId) {
        List<AdminDirectory.RoleHolder> holders = admin.lockActiveHoldersOf(ROLE);
        if (!satisfied(holders)) {
            return;
        }
        // Everyone stationed here loses a valid station when it goes.
        List<AdminDirectory.RoleHolder> remaining = holders.stream()
                .filter(h -> !receptionId.equals(h.receptionId()))
                .toList();
        if (!satisfied(remaining)) {
            throw new WouldStrandMasterAdmin(receptionId);
        }
    }

    public static final class WouldStrandMasterAdmin extends RuntimeException {
        public WouldStrandMasterAdmin(UUID receptionId) {
            super("Refused: reception " + receptionId + " is where the only active Master Admin is "
                    + "stationed, and FR-ADM-01 (SRS B1) places that authority at a central "
                    + "reception. Assign another Master Admin first, or move this one.");
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
