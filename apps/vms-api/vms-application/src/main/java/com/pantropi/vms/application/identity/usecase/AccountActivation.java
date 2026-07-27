package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.ActivationStore;
import com.pantropi.vms.application.identity.port.PasswordHasher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Out-of-band account activation (US-02.2.2, T-02.2.2.3).
 *
 * <p>An administrator never sets or transmits a password. Provisioning issues a single-use,
 * time-limited activation token (delivery is a stub until the email channel arrives in Phase 4,
 * F-16.2); the user redeems it to set their own initial password subject to the interim policy.
 * Only a SHA-256 hash of the token is stored; the raw token is never logged.
 */
public final class AccountActivation {

    /** Interim password policy; F-02.3 will formalise and replace it. */
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final ActivationStore store;
    private final PasswordHasher passwordHasher;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public AccountActivation(ActivationStore store, PasswordHasher passwordHasher, Clock clock,
                             Duration ttl) {
        this.store = store;
        this.passwordHasher = passwordHasher;
        this.clock = clock;
        this.ttl = ttl;
    }

    /** Issue an activation token for a user and return the raw token for out-of-band delivery. */
    public String issueToken(UUID userId) {
        String rawToken = UUID.randomUUID() + "." + randomSecret();
        store.createToken(userId, sha256(rawToken), clock.instant().plus(ttl));
        return rawToken;
    }

    /**
     * Redeem a token and set the initial password.
     *
     * @throws InvalidToken if the token is unknown, expired or already redeemed
     * @throws WeakPassword if the password fails the interim policy
     */
    public void activate(String rawToken, char[] password) {
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new WeakPassword(MIN_PASSWORD_LENGTH);
        }
        Optional<UUID> userId = rawToken == null
                ? Optional.empty() : store.redeem(sha256(rawToken), clock.instant());
        UUID activated = userId.orElseThrow(InvalidToken::new);
        store.setPassword(activated, passwordHasher.hash(password));
    }

    private String randomSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static final class InvalidToken extends RuntimeException {
        public InvalidToken() { super("Invalid or expired activation token"); }
    }

    public static final class WeakPassword extends RuntimeException {
        public WeakPassword(int min) { super("Password must be at least " + min + " characters"); }
    }
}
