package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.CredentialStore;
import com.pantropi.vms.application.identity.port.LoginAttemptStore;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.SessionStore;

import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;

/**
 * Self-service password change (US-02.3.1, T-02.3.1.3) — NFR-SEC-01 (SRS B1), OWASP ASVS V2.
 *
 * <p>Requires the current password, so possession of a live access token is not by itself enough to
 * take over an account — a stolen token cannot be used to lock the real owner out.
 *
 * <p>On success <strong>every</strong> session of the user is revoked, including the one making the
 * request. A password change is the standard response to a suspected compromise, so leaving any
 * existing session alive would defeat the point; the caller opens a fresh session and returns the
 * new token pair, so the user is not made to log in again.
 *
 * <p>The lockout counter is cleared too: a user who proved knowledge of the current password and
 * chose a new one should not stay locked out by earlier failed guesses.
 *
 * <p>Pure orchestration over ports — no framework. Password arrays are wiped after use.
 */
public final class ChangePassword {

    private final CredentialStore credentials;
    private final PasswordHasher passwordHasher;
    private final PasswordPolicy policy;
    private final SessionStore sessions;
    private final LoginAttemptStore attempts;
    private final AuditTrail audit;
    private final Clock clock;

    public ChangePassword(CredentialStore credentials, PasswordHasher passwordHasher,
                          PasswordPolicy policy, SessionStore sessions, LoginAttemptStore attempts,
                          AuditTrail audit, Clock clock) {
        this.credentials = credentials;
        this.passwordHasher = passwordHasher;
        this.policy = policy;
        this.sessions = sessions;
        this.attempts = attempts;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @throws AuthenticateUser.InvalidCredentials if the current password is wrong
     * @throws PasswordPolicy.WeakPassword         if the proposed password fails policy
     * @throws PasswordUnchanged                   if the proposed password equals the current one
     */
    public void change(UUID userId, char[] currentPassword, char[] newPassword) {
        try {
            CredentialStore.Credential credential = credentials.findById(userId)
                    .orElseThrow(AuthenticateUser.InvalidCredentials::new);

            if (!passwordHasher.matches(currentPassword, credential.passwordHash())) {
                // Audited: repeated failures here are a signal, and the actor is already known.
                audit.record(userId, "password.change_denied", "user", userId.toString(),
                        "current password did not match");
                throw new AuthenticateUser.InvalidCredentials();
            }

            // Policy first, then the reuse check — a user who retyped their existing password gets
            // the specific reason rather than a generic strength complaint.
            policy.validate(newPassword);
            if (passwordHasher.matches(newPassword, credential.passwordHash())) {
                throw new PasswordUnchanged();
            }

            credentials.updatePassword(userId, passwordHasher.hash(newPassword), clock.instant());
            attempts.recordSuccess(credential.username());
            int revoked = sessions.revokeAllForUser(userId, "password_changed");

            // Never the password, never the hash — only that it happened, and how far it reached.
            audit.record(userId, "password.changed", "user", userId.toString(),
                    revoked + " session(s) revoked");
        } finally {
            wipe(currentPassword);
            wipe(newPassword);
        }
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    /** Rejecting a no-op change keeps the rotation stamp honest. */
    public static final class PasswordUnchanged extends RuntimeException {
        public PasswordUnchanged() {
            super("The new password must be different from the current one");
        }
    }
}
