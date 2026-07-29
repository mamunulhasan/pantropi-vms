package com.pantropi.vms.application.identity.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for administration queries and the bootstrap insert (US-02.4.1).
 *
 * <p>Implemented by a JDBC adapter over {@code vms.users}, {@code vms.roles},
 * {@code vms.receptions}. The application services below know only this interface.
 */
public interface AdminDirectory {

    long countUsers();

    long countActiveUsersWithRole(String roleCode);

    Optional<UUID> roleIdByCode(String roleCode);

    /** True only when the reception exists and its {@code is_central} flag is set. */
    boolean receptionIsCentral(UUID receptionId);

    /**
     * The active holders of a role and where each is stationed, <strong>locked for the calling
     * transaction</strong> (US-04.4.1, T-04.4.1.3).
     *
     * <p>This exists so the two guards protecting the FR-ADM-01 (SRS B1) authority contend on the
     * same rows. One refuses deactivating the last Master Admin; the other refuses deactivating the
     * central reception the last Master Admin is stationed at. Each is correct alone and useless
     * alone: run concurrently against unlocked reads, both see two admins, both allow, and the
     * survivor is left without a reception.
     *
     * <p>Taking a row lock on the same set makes the second caller wait and re-read, so the pair
     * cannot be defeated by ordering. Must be called inside a transaction, or the lock is released
     * immediately and the guarantee is lost.
     */
    List<RoleHolder> lockActiveHoldersOf(String roleCode);

    /**
     * An active holder of a role and where they are stationed.
     *
     * @param receptionId  may be null — nothing forces a role holder to have a reception
     * @param stationValid the reception exists, is active, and is flagged central. This is what
     *                     FR-ADM-01 (SRS B1) actually requires of a Master Admin's posting, and
     *                     carrying it here lets the guards reason about the state that results from
     *                     an operation rather than merely counting holders.
     */
    record RoleHolder(UUID userId, UUID receptionId, boolean stationValid) {}

    /** Insert a user. Used only by the first-run bootstrap; throws if the username exists. */
    void insertUser(UUID id, String username, String passwordHash, UUID roleId,
                    UUID receptionId, boolean active);
}
