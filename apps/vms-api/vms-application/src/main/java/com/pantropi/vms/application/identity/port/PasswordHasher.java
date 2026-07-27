package com.pantropi.vms.application.identity.port;

/**
 * Outbound port: password hashing and verification (US-02.1.1).
 *
 * <p>The algorithm lives in an infrastructure adapter. Swapping PBKDF2 for bcrypt/argon2
 * (when a library is available) is a one-adapter change, invisible to the use case.
 */
public interface PasswordHasher {

    /** Encode a raw password into a self-describing storable hash string. */
    String hash(char[] rawPassword);

    /**
     * Verify a raw password against a stored hash in constant time with respect to the
     * comparison. Returns false rather than throwing on a malformed stored hash.
     */
    boolean matches(char[] rawPassword, String storedHash);
}
