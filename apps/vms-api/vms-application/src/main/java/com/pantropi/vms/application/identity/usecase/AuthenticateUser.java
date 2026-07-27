package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.LoginAttemptStore;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.UserDirectory;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Login credential check with lockout (US-02.1.1, US-02.3.1) — FR-ADM-02 (SRS B1),
 * NFR-SEC-01 (SRS B1), OWASP ASVS V2.
 *
 * <p>Verifies a username/password and returns the authenticated identity; session issuance is the
 * caller's next step ({@link SessionManager}).
 *
 * <h2>Indistinguishable failures (US-02.3.1 AC-5)</h2>
 * A locked account, a wrong password and an account that does not exist must be impossible to tell
 * apart. Three properties are maintained deliberately:
 * <ul>
 *   <li><strong>Same exception</strong> — every failure throws {@link InvalidCredentials}; nothing
 *       distinguishes them in status or body.</li>
 *   <li><strong>Same work</strong> — every path performs exactly one password hash. The hash
 *       dominates the response time by orders of magnitude, so the timing envelope matches whether
 *       or not the user exists. An unknown user is compared against a dummy hash for this reason.</li>
 *   <li><strong>Same counting</strong> — failures are counted per <em>username</em>, so an unknown
 *       account locks out exactly like a real one and probing cannot distinguish them.</li>
 * </ul>
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class AuthenticateUser {

    private static final String DUMMY_HASH =
            "pbkdf2_sha256$210000$AAAAAAAAAAAAAAAAAAAAAA$" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final UserDirectory users;
    private final PasswordHasher passwordHasher;
    private final LoginAttemptStore attempts;
    private final AuditTrail audit;
    private final Clock clock;
    private final int lockThreshold;
    private final Duration lockWindow;

    public AuthenticateUser(UserDirectory users, PasswordHasher passwordHasher,
                            LoginAttemptStore attempts, AuditTrail audit, Clock clock,
                            int lockThreshold, Duration lockWindow) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.attempts = attempts;
        this.audit = audit;
        this.clock = clock;
        if (lockThreshold < 2) {
            // A threshold of 1 would lock every account on its first typo, and the store's upsert
            // cannot express it anyway (the insert path starts the counter at 1). Fail at startup
            // rather than misbehave under a misconfiguration.
            throw new IllegalArgumentException("lockout threshold must be at least 2");
        }
        this.lockThreshold = lockThreshold;
        this.lockWindow = lockWindow;
    }

    /** @throws InvalidCredentials on any authentication failure — uniform, no detail leaked */
    public AuthenticatedUser login(String username, char[] password) {
        String key = username == null ? "" : username.trim();
        Optional<UserDirectory.AuthUser> found =
                key.isEmpty() ? Optional.empty() : users.findActiveByUsername(key);
        UserDirectory.AuthUser user = found.orElse(null);

        // Locked: still spend a hash so the response time matches the other failure paths, and
        // do not extend the lock — a locked account must not be extendable by continued probing.
        if (attempts.isLocked(key, clock.instant())) {
            passwordHasher.matches(password, DUMMY_HASH);
            throw new InvalidCredentials();
        }

        boolean ok = user == null
                ? falseAfter(passwordHasher.matches(password, DUMMY_HASH))   // constant-time decoy
                : passwordHasher.matches(password, user.passwordHash());

        if (!ok) {
            // Counted by username, so an unknown account behaves exactly like a real one.
            boolean nowLocked = attempts.recordFailure(key, clock.instant(), lockThreshold, lockWindow);
            if (nowLocked) {
                // Audited against the account only when one exists; an unknown username produces
                // an anonymous entry rather than confirming absence.
                audit.record(user == null ? null : user.id(), "account.locked", "user",
                        user == null ? "unknown" : user.id().toString(),
                        "locked after " + lockThreshold + " consecutive failures");
            }
            throw new InvalidCredentials();
        }

        attempts.recordSuccess(key);
        return new AuthenticatedUser(user.id(), user.username(), user.roleCode(),
                user.mustChangePassword());
    }

    /** Consumes the decoy result so the compiler cannot elide the comparison. */
    private static boolean falseAfter(boolean decoyResult) {
        return decoyResult && false;
    }

    public record AuthenticatedUser(UUID id, String username, String roleCode,
                                    boolean mustChangePassword) {}

    public static final class InvalidCredentials extends RuntimeException {
        public InvalidCredentials() {
            super("Invalid username or password");
        }
    }
}
