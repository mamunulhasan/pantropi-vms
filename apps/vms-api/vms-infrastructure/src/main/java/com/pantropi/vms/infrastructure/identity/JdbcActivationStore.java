package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.ActivationStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link ActivationStore} over {@code vms.activation_tokens} (US-02.2.2).
 *
 * <p>{@link #redeem} is atomic and single-use: a conditional UPDATE stamps {@code redeemed_at} only
 * for a token that matches, is unexpired and not yet redeemed, returning the user id. A replay or
 * an expired token matches nothing.
 */
public final class JdbcActivationStore implements ActivationStore {

    private final JdbcTemplate jdbc;

    public JdbcActivationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createToken(UUID userId, String tokenHash, Instant expiresAt) {
        jdbc.update("""
                INSERT INTO vms.activation_tokens (user_id, token_hash, expires_at)
                VALUES (?, ?, ?)
                """, userId, tokenHash, Timestamp.from(expiresAt));
    }

    @Override
    public Optional<UUID> redeem(String tokenHash, Instant now) {
        return jdbc.query("""
                UPDATE vms.activation_tokens
                SET redeemed_at = ?
                WHERE token_hash = ? AND redeemed_at IS NULL AND expires_at > ?
                RETURNING user_id
                """, (rs, i) -> rs.getObject("user_id", UUID.class),
                Timestamp.from(now), tokenHash, Timestamp.from(now)).stream().findFirst();
    }

    @Override
    public void setPassword(UUID userId, String passwordHash) {
        jdbc.update("UPDATE vms.users SET password_hash = ?, updated_at = now() WHERE id = ?",
                passwordHash, userId);
    }
}
