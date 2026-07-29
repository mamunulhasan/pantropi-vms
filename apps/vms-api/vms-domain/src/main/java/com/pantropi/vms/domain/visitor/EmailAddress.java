package com.pantropi.vms.domain.visitor;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A visitor's email address (US-07.1.2, T-07.1.2.1) — FR-VMS-01 (SRS B1).
 *
 * <p>A value object rather than a {@code String} for three reasons the story asks for directly:
 * normalisation happens once at construction, two differently-cased spellings of the same address
 * compare equal, and {@link #toString()} cannot leak the address into a log line.
 *
 * <h2>Normalisation (AC-2)</h2>
 * Trimmed, and lower-cased in the <em>root</em> locale. {@code String.toLowerCase()} with a Turkish
 * default locale maps {@code I} to {@code ı}, so {@code ADA@X.COM} and {@code ada@x.com} would stop
 * being the same address depending on where the server happens to run. The column is {@code citext},
 * which compares case-insensitively in the database; normalising here means Java agrees with it.
 *
 * <p>This address is the delivery target for pass delivery (F-09.6), so "the same person" has to
 * mean the same thing on both sides.
 *
 * <h2>Validation is deliberately shallow</h2>
 * One {@code @}, something before it, a dotted something after it, no whitespace. Not RFC 5322 —
 * that grammar admits addresses no mail system accepts and rejecting a valid oddity would keep a
 * real visitor out of the building. The check exists to catch a typed mistake, and delivery failure
 * is what catches the rest.
 *
 * <p>Pure Java: no framework.
 */
public final class EmailAddress {

    /** RFC 5321's practical maximum; the column is unbounded {@code citext}. */
    public static final int MAX_LENGTH = 320;

    private static final Pattern SHAPE =
            Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$");

    private final String value;

    private EmailAddress(String value) {
        this.value = value;
    }

    /**
     * @return null when nothing was given — an email is optional on a visitor
     * @throws Visitor.InvalidVisitorDetail if present but malformed or over-long; the message names
     *                                      the field and never echoes the value (AC-4)
     */
    public static EmailAddress of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalised = raw.trim().toLowerCase(Locale.ROOT);
        if (normalised.length() > MAX_LENGTH) {
            throw new Visitor.InvalidVisitorDetail("visitor email",
                    "must be at most " + MAX_LENGTH + " characters");
        }
        if (!SHAPE.matcher(normalised).matches()) {
            throw new Visitor.InvalidVisitorDetail("visitor email", "is not a valid email address");
        }
        return new EmailAddress(normalised);
    }

    /** Rehydrate a stored address without re-validating: what is in the database is what it is. */
    public static EmailAddress stored(String value) {
        return value == null || value.isBlank() ? null : new EmailAddress(value);
    }

    /** The normalised address. Call this only where the actual value is needed — never to log. */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EmailAddress that && value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /**
     * Redacted (T-07.1.2.1).
     *
     * <p>The domain of the address survives, because that is often what makes a log useful — which
     * tenant's mail server, which typo'd domain — while the part that identifies a person does not.
     * Every accidental interpolation into a log line, an exception message or a {@code toString} of
     * some enclosing object goes through here.
     */
    @Override
    public String toString() {
        int at = value.indexOf('@');
        return at < 0 ? "«email»" : "«email»@" + value.substring(at + 1);
    }
}
