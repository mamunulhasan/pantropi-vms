package com.pantropi.vms.application.identity.usecase;

import java.util.Locale;
import java.util.Set;

/**
 * Password strength rules, applied wherever a password is set (US-02.3.1, T-02.3.1.1) —
 * NFR-SEC-01 (SRS B1), OWASP ASVS V2.
 *
 * <p>Follows current OWASP guidance: length is the primary control, composition rules (one upper,
 * one digit, one symbol) are deliberately <em>not</em> imposed because they push users toward
 * predictable substitutions without adding real entropy. What is checked instead is length, a
 * breached-password denylist, and trivial patterns.
 *
 * <p><strong>The rejection message never says which rule matched a denylist entry</strong> (AC-1).
 * Telling a user "that password appears in a breach list" is useful; telling them <em>which</em>
 * entry, or echoing the value, is not — and the value must never be logged.
 *
 * <p>Pure Java: no framework, no I/O.
 */
public final class PasswordPolicy {

    /**
     * A small embedded denylist of the passwords that dominate every credential-stuffing corpus.
     * A production deployment should point at a full breached-password set — for example a
     * k-anonymity range query against Have I Been Pwned — behind this same method. That is not
     * reachable from this build (no outbound service call is available), so this list is a
     * deliberate, documented interim rather than the intended end state.
     */
    private static final Set<String> DENYLIST = Set.of(
            "password", "password1", "password123", "passw0rd", "p@ssword", "p@ssw0rd",
            "123456", "1234567", "12345678", "123456789", "1234567890", "qwerty", "qwerty123",
            "letmein", "welcome", "welcome1", "admin", "admin123", "administrator", "root",
            "changeme", "iloveyou", "monkey", "dragon", "sunshine", "princess", "football",
            "abc123", "111111", "000000", "trustno1", "master", "secret", "pass", "test",
            "vms", "vmsadmin", "pantropi", "westgate");

    private final int minimumLength;
    private final int maximumLength;

    public PasswordPolicy(int minimumLength, int maximumLength) {
        this.minimumLength = minimumLength;
        this.maximumLength = maximumLength;
    }

    /** Default policy: 12 characters minimum, 200 maximum (bounded to prevent hashing abuse). */
    public static PasswordPolicy standard() {
        return new PasswordPolicy(12, 200);
    }

    /**
     * @throws WeakPassword when the password fails any rule. The message is generic about
     *                      denylisting and never contains the candidate value.
     */
    public void validate(char[] password) {
        if (password == null || password.length < minimumLength) {
            throw new WeakPassword("Password must be at least " + minimumLength + " characters.");
        }
        if (password.length > maximumLength) {
            throw new WeakPassword("Password must be at most " + maximumLength + " characters.");
        }

        String candidate = new String(password);
        String normalised = candidate.toLowerCase(Locale.ROOT).trim();

        if (DENYLIST.contains(normalised) || DENYLIST.contains(stripTrailingDigits(normalised))) {
            // Deliberately does not name the matching entry (AC-1).
            throw new WeakPassword("That password is too common. Choose something less predictable.");
        }
        // All-digit passwords are the one case where length is not enough: twelve digits is about
        // forty bits, which a modern cracker exhausts, and they are the shape people choose when
        // reaching for a date or a phone number.
        if (isAllDigits(normalised)
                || isSingleRepeatedCharacter(candidate) || isSequential(normalised)) {
            throw new WeakPassword("That password is too predictable. Choose something less obvious.");
        }
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripTrailingDigits(String value) {
        int end = value.length();
        while (end > 0 && Character.isDigit(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static boolean isSingleRepeatedCharacter(String value) {
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) != value.charAt(0)) {
                return false;
            }
        }
        return true;
    }

    /** Runs of consecutive characters, ascending or descending — "abcdefghijkl", "9876543210…". */
    private static boolean isSequential(String value) {
        if (value.length() < 2) {
            return false;
        }
        int direction = Integer.signum(value.charAt(1) - value.charAt(0));
        if (direction == 0) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) - value.charAt(i - 1) != direction) {
                return false;
            }
        }
        return true;
    }

    /** Rejection carrying user-facing guidance only — never the candidate password. */
    public static final class WeakPassword extends RuntimeException {
        public WeakPassword(String guidance) {
            super(guidance);
        }
    }
}
