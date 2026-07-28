package com.pantropi.vms.application.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for reading and replacing a user's own password (US-02.3.1, T-02.3.1.3).
 *
 * <p>Separate from {@link UserDirectory}, which answers "who is logging in", and from
 * {@link UserAdministrationStore}, which never touches password material at all. Keeping the
 * credential path to one narrow port makes it easy to assert that nothing else can write a hash.
 *
 * <p>Note what is <strong>absent</strong>: there is no way for one user to set another's password.
 * An administrator recovers an account by issuing an activation token (US-02.2.2), never by
 * choosing a value — so no such method exists to be misused.
 */
public interface CredentialStore {

    /** Current credential of an active user, or empty if unknown or deactivated. */
    Optional<Credential> findById(UUID userId);

    /**
     * Replace the password hash, stamp the rotation clock, and clear any forced-change flag.
     * One statement, so a partial state (new hash, stale flag) cannot be observed.
     */
    void updatePassword(UUID userId, String newHash, Instant changedAt);

    /** Require a password change before the account can reach anything else (administrator reset). */
    void requirePasswordChange(UUID userId);

    /** Username needed to clear the lockout counter, which is keyed by username. */
    record Credential(UUID userId, String username, String passwordHash) {}
}
