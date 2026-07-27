package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.CredentialStore;
import com.pantropi.vms.application.identity.port.LoginAttemptStore;
import com.pantropi.vms.application.identity.port.SessionStore;

import java.util.UUID;

/**
 * Administrator account recovery: unlock, and reset (US-02.3.1) — FR-ADM-02 (SRS B1).
 *
 * <p>Both operations are guarded by {@code user.manage} at the edge.
 *
 * <h2>An administrator never sets a password</h2>
 * {@link #reset} issues a single-use activation token (reusing the US-02.2.2 mechanism) for
 * out-of-band delivery and marks the account as requiring a change. It cannot choose a value —
 * {@link CredentialStore} has no method for it. So an administrator can restore access without ever
 * knowing, transmitting or being able to reuse a user's password, and the audit trail cannot be
 * repudiated by pointing at the administrator.
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class AccountRecovery {

    private final CredentialStore credentials;
    private final LoginAttemptStore attempts;
    private final SessionStore sessions;
    private final AccountActivation activation;
    private final AuditTrail audit;

    public AccountRecovery(CredentialStore credentials, LoginAttemptStore attempts,
                           SessionStore sessions, AccountActivation activation, AuditTrail audit) {
        this.credentials = credentials;
        this.attempts = attempts;
        this.sessions = sessions;
        this.activation = activation;
        this.audit = audit;
    }

    /**
     * Clear a lockout so the user can try again.
     *
     * @throws UnknownAccount if no active user has that id
     */
    public void unlock(UUID actorId, UUID userId) {
        CredentialStore.Credential credential = require(userId);
        attempts.unlock(credential.username());
        audit.record(actorId, "account.unlocked", "user", userId.toString(),
                "lockout cleared by administrator");
    }

    /**
     * Issue an activation token and force a password change, revoking every existing session.
     *
     * @return the raw token, for out-of-band delivery — <strong>never log or audit this value</strong>
     * @throws UnknownAccount if no active user has that id
     */
    public String reset(UUID actorId, UUID userId) {
        CredentialStore.Credential credential = require(userId);

        String token = activation.issueToken(userId);
        credentials.requirePasswordChange(userId);
        attempts.unlock(credential.username());
        int revoked = sessions.revokeAllForUser(userId, "password_reset");

        audit.record(actorId, "password.reset_requested", "user", userId.toString(),
                "activation token issued; " + revoked + " session(s) revoked");
        return token;
    }

    private CredentialStore.Credential require(UUID userId) {
        return credentials.findById(userId).orElseThrow(UnknownAccount::new);
    }

    /**
     * Raised for an id that matches no active user. Safe to surface as 404 here: reaching this code
     * already required {@code user.manage}, so it tells an administrator nothing they could not
     * learn from the user list they are entitled to read.
     */
    public static final class UnknownAccount extends RuntimeException {
        public UnknownAccount() {
            super("No such active user");
        }
    }
}
