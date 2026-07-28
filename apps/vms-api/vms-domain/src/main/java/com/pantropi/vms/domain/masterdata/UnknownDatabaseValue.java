package com.pantropi.vms.domain.masterdata;

/**
 * A stored enum value this code does not recognise (US-04.6.1, T-04.6.1.2).
 *
 * <p>Raised when reading, never when validating input. It means the database's enum type has gained
 * a value the application was not taught about — a migration applied without the matching code, or
 * a hand-edited row.
 *
 * <p><strong>This is deliberately fatal to the read rather than survivable.</strong> The tempting
 * alternative is to fall back to a default and carry on, which for a credential type would mean
 * silently issuing a QR pass where an RFID card was configured. Nobody would see that until a
 * visitor stood at a turnstile holding the wrong thing. A loud failure at the read is recoverable in
 * minutes; a silent substitution is not recoverable at all, because nothing records that it
 * happened.
 *
 * <p>Carries the permitted values so the operator reading the log knows what the code understands
 * and can compare it against the database.
 */
public final class UnknownDatabaseValue extends RuntimeException {

    public final String type;
    public final String value;

    public UnknownDatabaseValue(String type, String value, String permitted) {
        super("Stored " + type + " value '" + value + "' is not one this application understands ("
                + permitted + "). The database enum and the code have diverged.");
        this.type = type;
        this.value = value;
    }
}
