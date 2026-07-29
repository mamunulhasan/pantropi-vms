package com.pantropi.vms.domain.visitor;

import java.util.regex.Pattern;

/**
 * A visitor's phone number (US-07.1.2, T-07.1.2.1) — FR-VMS-01 (SRS B1).
 *
 * <p>Like {@link EmailAddress}: normalised once, compares by value, and redacted in
 * {@link #toString()} so it cannot reach a log line by accident. This is the delivery target for a
 * WhatsApp pass (TODO-05), so the same number written two ways has to be one number.
 *
 * <h2>Normalisation: keep the digits and a leading plus</h2>
 * Spaces, hyphens, brackets and dots are presentation. {@code +880 1000-00000} and
 * {@code +8801000 00000} are the same number, and storing them differently means a duplicate check
 * that never fires and a delivery that goes twice.
 *
 * <p><strong>Not</strong> parsed into a country code and subscriber number. That needs a
 * region-aware library (libphonenumber), which is not obtainable in this build, and a hand-rolled
 * approximation of one is worse than none: it would confidently reject valid numbers from whichever
 * countries the author did not think about. Singapore and Bangladesh numbers both have to work here.
 *
 * <p>Pure Java: no framework.
 */
public final class PhoneNumber {

    /** Comfortably above E.164's 15 digits, with room for the punctuation callers type. */
    public static final int MAX_LENGTH = 40;

    /** After normalisation: an optional leading {@code +} and then digits only. */
    private static final Pattern NORMALISED = Pattern.compile("^\\+?\\d{6,20}$");

    private static final Pattern PRESENTATION = Pattern.compile("[\\s().\\-]");

    private final String value;

    private PhoneNumber(String value) {
        this.value = value;
    }

    /**
     * @return null when nothing was given — a phone number is optional on a visitor
     * @throws Visitor.InvalidVisitorDetail if present but malformed or over-long; the message names
     *                                      the field and never echoes the value (AC-4)
     */
    public static PhoneNumber of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new Visitor.InvalidVisitorDetail("visitor phone",
                    "must be at most " + MAX_LENGTH + " characters");
        }
        String normalised = PRESENTATION.matcher(trimmed).replaceAll("");
        if (!NORMALISED.matcher(normalised).matches()) {
            throw new Visitor.InvalidVisitorDetail("visitor phone", "is not a valid phone number");
        }
        return new PhoneNumber(normalised);
    }

    /** Rehydrate a stored number without re-validating. */
    public static PhoneNumber stored(String value) {
        return value == null || value.isBlank() ? null : new PhoneNumber(value);
    }

    /** The normalised number. Call this only where the actual value is needed — never to log. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PhoneNumber that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Redacted (T-07.1.2.1), keeping only the last two digits.
     *
     * <p>Enough to tell two numbers apart while reading a log, not enough to be one. A country code
     * is deliberately not kept: in a single-building system almost every number shares one, so it
     * would narrow nothing while making the redaction look more generous than it is.
     */
    @Override
    public String toString() {
        return value.length() <= 2 ? "«phone»" : "«phone»…" + value.substring(value.length() - 2);
    }
}
