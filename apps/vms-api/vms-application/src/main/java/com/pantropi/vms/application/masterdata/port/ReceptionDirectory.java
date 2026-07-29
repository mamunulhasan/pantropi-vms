package com.pantropi.vms.application.masterdata.port;

import com.pantropi.vms.domain.masterdata.Reception;

import java.util.Optional;
import java.util.UUID;

/**
 * Reception queries and writes the shared master data port cannot express (US-04.4.1).
 *
 * <p>Two things are specific to receptions: the singular central designation, and the count of users
 * stationed at one.
 */
public interface ReceptionDirectory {

    /**
     * The reception currently flagged central, if any. Locked for the calling transaction, so a
     * concurrent transfer waits rather than both reading "the old holder" and both clearing it.
     */
    Optional<Reception> lockCurrentCentral();

    /** Set or clear the designation on one reception. Called only from inside a transfer. */
    void setCentral(UUID receptionId, boolean central);

    /**
     * Active users stationed at this reception (AC-5).
     *
     * <p>A snapshot for the administrator, not a gate — deactivating a reception with users
     * assigned is permitted; leaving them without warning is not.
     */
    int activeUserCount(UUID receptionId);
}
