package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.LoginAttemptStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * JDBC adapter for {@link LoginAttemptStore} over {@code vms.login_attempts} (US-02.3.1).
 *
 * <p>{@link #recordFailure} is a single {@code INSERT … ON CONFLICT DO UPDATE … RETURNING}. That
 * matters: concurrent login attempts for the same username are serialised by the row lock the
 * upsert takes, so parallel guessing cannot race past the threshold — a read-then-write would let
 * N simultaneous attempts all observe {@code failed_count = threshold - 1} and none of them lock.
 *
 * <p>Rows are keyed by username and created for usernames that do not exist, deliberately: the
 * lockout must not become a username oracle (AC-5). This is a bounded write amplification — one
 * narrow row per distinct attempted username — and {@code idx_login_attempts_locked} keeps the
 * lock scan cheap.
 */
public final class JdbcLoginAttemptStore implements LoginAttemptStore {

    private final JdbcTemplate jdbc;

    public JdbcLoginAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isLocked(String username, Instant now) {
        List<Boolean> rows = jdbc.query("""
                SELECT locked_until > ? AS locked FROM vms.login_attempts WHERE username = ?
                """, (rs, i) -> rs.getBoolean("locked"), Timestamp.from(now), username);
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    @Override
    public boolean recordFailure(String username, Instant now, int threshold, Duration lockWindow) {
        // The counter resets on a lock so the next lock needs a full threshold of new failures,
        // rather than one further attempt re-locking an account that has just been released.
        List<Boolean> rows = jdbc.query("""
                INSERT INTO vms.login_attempts (username, failed_count, last_attempt_at, updated_at)
                VALUES (?, 1, ?, ?)
                ON CONFLICT (username) DO UPDATE SET
                    failed_count = CASE
                        WHEN vms.login_attempts.failed_count + 1 >= ? THEN 0
                        ELSE vms.login_attempts.failed_count + 1 END,
                    locked_until = CASE
                        WHEN vms.login_attempts.failed_count + 1 >= ? THEN ?
                        ELSE vms.login_attempts.locked_until END,
                    last_attempt_at = ?,
                    updated_at = ?
                RETURNING locked_until IS NOT NULL AND locked_until > ? AS locked
                """, (rs, i) -> rs.getBoolean("locked"),
                username, Timestamp.from(now), Timestamp.from(now),
                threshold,
                threshold, Timestamp.from(now.plus(lockWindow)),
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now));
        return !rows.isEmpty() && Boolean.TRUE.equals(rows.get(0));
    }

    @Override
    public void recordSuccess(String username) {
        jdbc.update("""
                UPDATE vms.login_attempts
                SET failed_count = 0, locked_until = NULL, updated_at = now()
                WHERE username = ?
                """, username);
    }

    @Override
    public void unlock(String username) {
        recordSuccess(username);
    }
}
