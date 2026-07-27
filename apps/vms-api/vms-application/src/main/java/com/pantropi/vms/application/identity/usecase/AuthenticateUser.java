package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.UserDirectory;

import java.util.Optional;
import java.util.UUID;

/**
 * Login credential check (US-02.1.1). Verifies a username/password and returns the authenticated
 * identity; session issuance is the caller's next step (US-02.1.2, {@link SessionManager}).
 *
 * <p>Requirement: FR-ADM-02 (SRS B1); supports NFR-SEC-01 (SRS B1).
 *
 * <p>Security posture (OWASP): fails uniformly for unknown user, wrong password and inactive
 * account so the endpoint is not a username oracle, and runs a hash comparison even when the user
 * is absent to blunt timing analysis. Pure orchestration over ports — no framework.
 */
public final class AuthenticateUser {

    private static final String DUMMY_HASH =
            "pbkdf2_sha256$210000$AAAAAAAAAAAAAAAAAAAAAA$" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final UserDirectory users;
    private final PasswordHasher passwordHasher;

    public AuthenticateUser(UserDirectory users, PasswordHasher passwordHasher) {
        this.users = users;
        this.passwordHasher = passwordHasher;
    }

    /** @throws InvalidCredentials on any authentication failure (uniform, no detail leaked) */
    public AuthenticatedUser login(String username, char[] password) {
        Optional<UserDirectory.AuthUser> found =
                username == null ? Optional.empty() : users.findActiveByUsername(username.trim());

        boolean ok;
        UserDirectory.AuthUser user = found.orElse(null);
        if (user == null) {
            passwordHasher.matches(password, DUMMY_HASH); // constant-time decoy
            ok = false;
        } else {
            ok = passwordHasher.matches(password, user.passwordHash());
        }

        if (!ok || user == null) {
            throw new InvalidCredentials();
        }
        return new AuthenticatedUser(user.id(), user.username(), user.roleCode());
    }

    public record AuthenticatedUser(UUID id, String username, String roleCode) {}

    public static final class InvalidCredentials extends RuntimeException {
        public InvalidCredentials() {
            super("Invalid username or password");
        }
    }
}
