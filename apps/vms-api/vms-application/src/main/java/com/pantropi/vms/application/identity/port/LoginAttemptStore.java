package com.pantropi.vms.application.identity.port;

import java.time.Duration;
import java.time.Instant;

/**
 * Outbound port for failed-login counting and account lockout (US-02.3.1, T-02.3.1.2).
 *
 * <p>Keyed by <strong>username, not user id</strong>, and deliberately so: an attempt against an
 * account that does not exist must be counted exactly like one against an account that does, or the
 * lockout behaviour itself becomes a username oracle (AC-5).
 *
 * <p>Realised over {@code vms.login_attempts}; a Redis adapter can replace it behind this interface.
 */
public interface LoginAttemptStore {

    /** True when the username is currently locked out. */
    boolean isLocked(String username, Instant now);

    /**
     * Record a failed attempt and lock the account when the threshold is reached.
     *
     * @return true when this attempt caused a lock to be applied
     */
    boolean recordFailure(String username, Instant now, int threshold, Duration lockWindow);

    /** Clear the counter after a successful authentication. */
    void recordSuccess(String username);

    /** Administrator unlock (guarded by {@code user.manage}). */
    void unlock(String username);
}
