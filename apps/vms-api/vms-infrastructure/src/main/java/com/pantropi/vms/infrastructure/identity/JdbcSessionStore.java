package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.SessionStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link SessionStore} over {@code vms.sessions} (US-02.1.2).
 *
 * <p>{@link #rotate} is atomic: a single conditional UPDATE swaps the refresh-token hash only when
 * the presented hash matches the stored current hash on a live session. Zero rows updated on a
 * live session means the presented hash was stale — a replay (AC-5). This gives single-use
 * rotation and replay detection without an explicit SERIALIZABLE transaction.
 */
public final class JdbcSessionStore implements SessionStore {

    private final JdbcTemplate jdbc;

    public JdbcSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(UUID sessionId, UUID userId, String username, String roleCode,
                       String refreshTokenHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO vms.sessions (id, user_id, username, role_code, refresh_token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, sessionId, userId, username, roleCode, refreshTokenHash, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<ActiveSession> findActive(UUID sessionId) {
        return jdbc.query("""
                SELECT id, user_id, username, role_code, expires_at FROM vms.sessions
                WHERE id = ? AND revoked = false AND expires_at > now()
                """, (rs, i) -> new ActiveSession(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("username"),
                rs.getString("role_code"),
                rs.getTimestamp("expires_at").toInstant()), sessionId).stream().findFirst();
    }

    @Override
    public RotationOutcome rotate(UUID sessionId, String presentedHash, String newHash,
                                  Instant newExpiry) {
        int updated = jdbc.update("""
                UPDATE vms.sessions
                SET refresh_token_hash = ?, expires_at = ?
                WHERE id = ? AND refresh_token_hash = ? AND revoked = false AND expires_at > now()
                """, newHash, Timestamp.from(newExpiry), sessionId, presentedHash);
        if (updated == 1) {
            return RotationOutcome.ROTATED;
        }
        // No row matched. If a live session still exists, the presented hash was stale → replay.
        return findActive(sessionId).isPresent()
                ? RotationOutcome.REPLAY_DETECTED : RotationOutcome.INVALID;
    }

    @Override
    public void revoke(UUID sessionId, String reason) {
        jdbc.update("""
                UPDATE vms.sessions SET revoked = true, revoked_reason = ?, revoked_at = now()
                WHERE id = ? AND revoked = false
                """, reason, sessionId);
    }

    @Override
    public int revokeAllForUser(UUID userId, String reason) {
        return jdbc.update("""
                UPDATE vms.sessions SET revoked = true, revoked_reason = ?, revoked_at = now()
                WHERE user_id = ? AND revoked = false
                """, reason, userId);
    }
}
