package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.UserDirectory;

import java.util.Optional;

/**
 * Login use case (US-02.1.1): verify credentials and issue an access token.
 *
 * <p>Requirement: FR-ADM-02 (SRS B1) — a VMS user authenticates and receives a session.
 * Supports NFR-SEC-01 (SRS B1).
 *
 * <p>Security posture (OWASP):
 * <ul>
 *   <li>Fails uniformly for unknown user, wrong password and inactive account — the caller
 *       cannot distinguish them, so the endpoint is not a username oracle.</li>
 *   <li>Runs a hash comparison even when the user does not exist, to blunt timing analysis.</li>
 *   <li>No framework here — pure orchestration over ports (US-01.2.1 boundary).</li>
 * </ul>
 */
public final class AuthenticateUser {

    /** A dummy PBKDF2 hash used to keep timing even when the user is absent. */
    private static final String DUMMY_HASH =
            "pbkdf2_sha256$210000$AAAAAAAAAAAAAAAAAAAAAA$" +
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private final UserDirectory users;
    private final PasswordHasher passwordHasher;
    private final AccessTokenIssuer tokenIssuer;

    public AuthenticateUser(UserDirectory users, PasswordHasher passwordHasher,
                            AccessTokenIssuer tokenIssuer) {
        this.users = users;
        this.passwordHasher = passwordHasher;
        this.tokenIssuer = tokenIssuer;
    }

    /**
     * @throws InvalidCredentials on any authentication failure (uniform, no detail leaked)
     */
    public AccessTokenIssuer.IssuedToken login(String username, char[] password) {
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
        return tokenIssuer.issue(user.id(), user.username(), user.roleCode());
    }

    /** Uniform authentication failure — never says which check failed. */
    public static final class InvalidCredentials extends RuntimeException {
        public InvalidCredentials() {
            super("Invalid username or password");
        }
    }
}
