package com.pantropi.vms.application.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the server-side session store (US-02.1.2).
 *
 * <p>The token-validation path consults {@link #findActive} so a revoked or expired session is
 * rejected before its access token would otherwise expire (AC-2, AC-3). Refresh rotation is
 * atomic via {@link #rotate} so a replayed refresh token is detected (AC-5).
 *
 * <p>Realised today by a JDBC adapter over {@code vms.sessions}; a Redis adapter can replace it
 * behind this interface (TDD §3/§7).
 */
public interface SessionStore {

    void create(UUID sessionId, UUID userId, String username, String roleCode,
                String refreshTokenHash, Instant expiresAt);

    /** Present (unrevoked, unexpired) session, else empty. Consulted on every protected request. */
    Optional<ActiveSession> findActive(UUID sessionId);

    /**
     * Atomically rotate the refresh token: succeed only if the presented hash matches the stored
     * current hash on a live session, replacing it with the new hash. The outcome distinguishes a
     * clean rotation, a replay (stale hash on a live session), and an invalid/expired session.
     */
    RotationOutcome rotate(UUID sessionId, String presentedHash, String newHash, Instant newExpiry);

    void revoke(UUID sessionId, String reason);

    /** Revoke every active session of a user — used when the user is deactivated (AC-3). */
    int revokeAllForUser(UUID userId, String reason);

    record ActiveSession(UUID sessionId, UUID userId, String username, String roleCode,
                         Instant expiresAt) {}

    enum RotationOutcome { ROTATED, REPLAY_DETECTED, INVALID }
}
