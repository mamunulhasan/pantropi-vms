package com.pantropi.vms.application.masterdata;

import java.util.List;
import java.util.Locale;

/**
 * Recognises values that look like a credential (US-04.8.2, T-04.8.2.2, AC-4).
 *
 * <p>{@code vms.system_settings} is readable over HTTP by anyone holding {@code masterdata.view} and
 * its changes are copied into the audit trail. That makes it a bad place for an API key — not
 * because it is insecure in itself, but because everything around it assumes the contents are
 * disclosable. Secrets belong in the F-05.2 secrets mechanism.
 *
 * <p>This is a <strong>heuristic, and deliberately a blunt one</strong>. It will not catch every
 * credential and is not the control that keeps secrets out — the closed key catalogue is, since
 * every catalogued key today is an integer or a boolean and cannot hold a token at all. This is the
 * second line: it exists so that the day someone adds a string-valued setting, pasting a key into it
 * fails loudly and points at the right mechanism, instead of quietly succeeding.
 *
 * <p>Consequently it errs toward refusing: a false positive costs an operator one puzzled moment and
 * a clear message, while a false negative puts a live credential somewhere it can be read back.
 *
 * <p>Pure Java: no framework, no I/O.
 */
public final class CredentialShape {

    /** Unmistakable markers — if one of these appears, the value is not a setting. */
    private static final List<String> MARKERS = List.of(
            "-----begin",          // PEM: private keys, certificates
            "bearer ",             // a bearer token pasted with its scheme
            "aws_secret",
            "akia",                // AWS access key id prefix
            "ghp_", "gho_", "ghs_",// GitHub tokens
            "sk_live_", "sk_test_",// Stripe secret keys
            "xoxb-", "xoxp-",      // Slack tokens
            "eyj",                 // a JWT: base64url of '{"' — every JWT starts this way
            "://",                 // a URI, which may carry credentials in its authority
            "password=", "passwd=", "secret=", "apikey=", "api_key=", "token=");

    /**
     * Length beyond which an unbroken, mixed-case-and-digit run stops looking like configuration.
     * Real settings are short and readable; a 32-character opaque blob is a key.
     */
    private static final int OPAQUE_MIN_LENGTH = 24;

    private CredentialShape() {
    }

    /** True when the value looks like a credential rather than a configuration value. */
    public static boolean looksLikeCredential(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return isOpaqueBlob(value.trim());
    }

    /**
     * A long unbroken token mixing letters and digits, or containing base64 padding — the shape of
     * something generated rather than written. Spaces disqualify it: a human-authored setting value
     * with spaces is prose, not a key.
     */
    private static boolean isOpaqueBlob(String value) {
        if (value.length() < OPAQUE_MIN_LENGTH || value.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        boolean hasLetter = value.chars().anyMatch(Character::isLetter);
        boolean hasDigit = value.chars().anyMatch(Character::isDigit);
        boolean base64ish = value.endsWith("=") || value.contains("+/");
        return (hasLetter && hasDigit) || base64ish;
    }

    /** The message an operator sees, naming where the value actually belongs. */
    public static String refusalMessage(String key) {
        return "The value for " + key + " looks like a credential. System settings are readable by "
                + "anyone holding masterdata.view and are copied into the audit trail, so they are "
                + "not an approved secret store. Use the secrets mechanism (F-05.2) instead.";
    }
}
