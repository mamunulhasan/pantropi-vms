package com.pantropi.vms.application.masterdata.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.usecase.MasterAdminPolicy;
import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.port.ReceptionDirectory;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.domain.masterdata.Reception;

import java.util.Optional;
import java.util.UUID;

/**
 * The two reception operations the shared master data pattern cannot express (US-04.4.1).
 *
 * <p>Ordinary create, update, list and reactivate go through
 * {@link MasterDataAdministration} unchanged. What lives here is the central designation, which is
 * singular across the installation, and deactivation, which has to consult the Master Admin
 * authority first.
 */
public final class ReceptionAdministration {

    private final MasterDataStore<Reception> store;
    private final ReceptionDirectory receptions;
    private final MasterAdminPolicy masterAdmins;
    private final TransactionRunner transactions;
    private final AuditTrail audit;

    public ReceptionAdministration(MasterDataStore<Reception> store, ReceptionDirectory receptions,
                                   MasterAdminPolicy masterAdmins, TransactionRunner transactions,
                                   AuditTrail audit) {
        this.store = store;
        this.receptions = receptions;
        this.masterAdmins = masterAdmins;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Move the central designation to this reception (AC-2).
     *
     * <p>One transaction: the current holder is read under a lock, cleared, and the new one set. If
     * any step fails the whole thing rolls back, because a half-applied transfer leaves the
     * installation with two central receptions or none — and nothing else in the system is written
     * to cope with either.
     *
     * <p>The audit records <strong>the previous holder</strong>, which is the part that cannot be
     * reconstructed afterwards: the row it was cleared from looks identical to one that never had
     * it.
     *
     * @param confirmed the caller has been shown which reception currently holds the designation
     *                  and has agreed to move it. A transfer is not something to do by accident, so
     *                  it does not happen without this.
     * @throws TransferNotConfirmed if another reception holds it and {@code confirmed} is false
     * @throws MasterDataStore.NotFound if the reception does not exist
     */
    public void designateCentral(UUID actorId, UUID receptionId, boolean confirmed) {
        transactions.run(() -> {
            Reception target = store.find(receptionId)
                    .orElseThrow(MasterDataStore.NotFound::new);

            Optional<Reception> currentHolder = receptions.lockCurrentCentral();

            if (currentHolder.isPresent() && currentHolder.get().id().equals(receptionId)) {
                return;   // already central; nothing to transfer and nothing to audit
            }
            if (currentHolder.isPresent() && !confirmed) {
                throw new TransferNotConfirmed(currentHolder.get().id(),
                        currentHolder.get().code());
            }

            currentHolder.ifPresent(held -> receptions.setCentral(held.id(), false));
            receptions.setCentral(receptionId, true);

            audit.recordChange(actorId, "reception.central_designated", "reception",
                    receptionId.toString(),
                    currentHolder.map(h -> "{\"previousHolder\":\"" + h.id()
                            + "\",\"previousCode\":\"" + h.code() + "\"}").orElse(null),
                    "{\"newHolder\":\"" + receptionId + "\",\"code\":\"" + target.code() + "\"}");
        });
    }

    /**
     * Deactivate a reception, refusing if it would strand the FR-ADM-01 (SRS B1) authority (AC-6).
     *
     * <p>The guard and the write are in one transaction, and the guard takes the same row lock that
     * user deactivation takes. That pairing is the requirement: each guard alone can be satisfied
     * while the invariant it protects is broken by the other operation running beside it.
     *
     * <p>Users stationed here keep their {@code reception_id} (AC-5). Nulling it would silently
     * unassign people as a side effect of retiring a location, and the assignment is worth more than
     * the tidiness.
     *
     * @return how many active users were stationed here, so the caller can report what it affected
     * @throws MasterAdminPolicy.WouldStrandMasterAdmin if this is the last holder's station
     */
    public int deactivate(UUID actorId, UUID receptionId) {
        return transactions.call(() -> {
            Reception before = store.find(receptionId).orElseThrow(MasterDataStore.NotFound::new);

            masterAdmins.assertReceptionDeactivatable(receptionId);

            int stationed = receptions.activeUserCount(receptionId);
            store.setActive(receptionId, false);

            audit.recordChange(actorId, "reception.deactivated", "reception",
                    receptionId.toString(),
                    "{\"code\":\"" + before.code() + "\",\"central\":" + before.central()
                            + ",\"active\":true}",
                    "{\"code\":\"" + before.code() + "\",\"central\":" + before.central()
                            + ",\"active\":false,\"usersStillAssigned\":" + stationed + "}");
            return stationed;
        });
    }

    /** Active users stationed at a reception, for the confirmation prompt (AC-5). */
    public int stationedUsers(UUID receptionId) {
        store.find(receptionId).orElseThrow(MasterDataStore.NotFound::new);
        return receptions.activeUserCount(receptionId);
    }

    /** Names the current holder, so the caller can show it before asking for confirmation. */
    public static final class TransferNotConfirmed extends RuntimeException {
        public final UUID currentHolderId;
        public final String currentHolderCode;

        public TransferNotConfirmed(UUID currentHolderId, String currentHolderCode) {
            super("Reception " + currentHolderCode + " currently holds the central designation. "
                    + "Confirm the transfer to move it.");
            this.currentHolderId = currentHolderId;
            this.currentHolderCode = currentHolderCode;
        }
    }
}
