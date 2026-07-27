package com.pantropi.vms.application.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for account-activation tokens (US-02.2.2, T-02.2.2.3).
 *
 * <p>Stores only a hash of each token. {@link #redeem} is atomic and single-use: it consumes an
 * unexpired, unredeemed token and returns the user it activates. Setting the password is separate
 * so the token store never sees password material.
 */
public interface ActivationStore {

    void createToken(UUID userId, String tokenHash, Instant expiresAt);

    /**
     * Atomically consume a token: succeed only for a token whose hash matches and which is neither
     * expired nor already redeemed, returning the activated user id.
     */
    Optional<UUID> redeem(String tokenHash, Instant now);

    /** Set the user's password hash (activation) — the only path that writes a real hash here. */
    void setPassword(UUID userId, String passwordHash);
}
