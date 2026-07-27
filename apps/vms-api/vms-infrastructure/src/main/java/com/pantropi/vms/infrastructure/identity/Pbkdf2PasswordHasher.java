package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.PasswordHasher;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * PBKDF2-HMAC-SHA256 password hasher (US-02.1.1) using only the JDK — no external library.
 *
 * <p>Stored format: {@code pbkdf2_sha256$<iterations>$<saltB64>$<hashB64>} (self-describing,
 * so the iteration count can be raised over time without breaking existing hashes).
 *
 * <p>To be replaced by bcrypt/argon2 via Spring Security when a library becomes available;
 * the swap is confined to this adapter. 210,000 iterations follows current OWASP guidance for
 * PBKDF2-HMAC-SHA256.
 */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

    private static final String ALG = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder b64 = Base64.getEncoder().withoutPadding();
    private final Base64.Decoder b64d = Base64.getDecoder();

    @Override
    public String hash(char[] rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] dk = pbkdf2(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(dk);
    }

    @Override
    public boolean matches(char[] rawPassword, String storedHash) {
        try {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4 || !parts[0].equals(PREFIX)) {
                return false;
            }
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = b64d.decode(parts[2]);
            byte[] expected = b64d.decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword, salt, iterations);
            return MessageDigest.isEqual(expected, actual); // constant-time
        } catch (RuntimeException e) {
            return false; // malformed stored hash is a non-match, never an error
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALG).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }
}
