package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.CredentialStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link CredentialStore} over {@code vms.users} (US-02.3.1).
 *
 * <p>{@link #updatePassword} writes the hash, the rotation stamp and the cleared forced-change flag
 * in one statement, so no interleaved read can see a new password still marked as needing changing.
 */
public final class JdbcCredentialStore implements CredentialStore {

    private final JdbcTemplate jdbc;

    public JdbcCredentialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Credential> findById(UUID userId) {
        return jdbc.query("""
                SELECT id, username, password_hash FROM vms.users
                WHERE id = ? AND is_active = true
                """, (rs, i) -> new Credential(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("password_hash")), userId).stream().findFirst();
    }

    @Override
    public void updatePassword(UUID userId, String newHash, Instant changedAt) {
        jdbc.update("""
                UPDATE vms.users
                SET password_hash = ?, password_changed_at = ?, must_change_password = false
                WHERE id = ? AND is_active = true
                """, newHash, Timestamp.from(changedAt), userId);
    }

    @Override
    public void requirePasswordChange(UUID userId) {
        jdbc.update("""
                UPDATE vms.users SET must_change_password = true WHERE id = ? AND is_active = true
                """, userId);
    }
}
