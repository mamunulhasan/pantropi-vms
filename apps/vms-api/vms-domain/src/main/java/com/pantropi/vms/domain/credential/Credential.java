package com.pantropi.vms.domain.credential;

import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;

import java.time.Instant;
import java.util.UUID;

/**
 * A visitor's pass (US-09.1.1), mirroring {@code vms.credentials}.
 *
 * <p>The lifecycle is the point. A credential is born {@code REQUESTED} — written before anything
 * leaves the building — and only becomes {@code ACTIVE} once ACS has answered with a reference. An
 * outbound call that succeeded while no row existed would be a credential opening a door that VMS
 * has no record of, which is why {@link #requested} and {@link #issued} are two steps and not one.
 *
 * <p>{@code FAILED} is terminal and deliberate: it means ACS refused in a way retrying cannot fix.
 * A transient failure leaves the credential {@code REQUESTED} instead, because that is the state
 * the retry machinery (US-11.3.1) will look for — marking it failed would quietly abandon a pass
 * that was only ever a timeout away from working.
 */
public final class Credential {

    /** Mirrors {@code vms.credential_state}. */
    public enum State {
        REQUESTED("requested"),
        ACTIVE("active"),
        EXPIRED("expired"),
        REVOKED("revoked"),
        CANCELLED("cancelled"),
        FAILED("failed");

        private final String dbValue;

        State(String dbValue) {
            this.dbValue = dbValue;
        }

        public String dbValue() {
            return dbValue;
        }

        public static State fromDatabase(String value) {
            for (State s : values()) {
                if (s.dbValue.equals(value)) {
                    return s;
                }
            }
            // Never a default: a state this build does not know is a schema change nobody told the
            // code about, and guessing would treat an unknown credential as usable.
            throw new IllegalStateException("Unknown credential state in the database: " + value);
        }
    }

    private final UUID id;
    private final UUID visitorId;
    private final UUID passTypeId;
    private final CredentialType credentialType;
    private final RestrictionType restriction;
    private final Instant validFrom;
    private final Instant validTo;
    private final State state;
    private final String acsCredentialId;
    private final String qrPayload;
    private final Instant issuedAt;

    private Credential(UUID id, UUID visitorId, UUID passTypeId, CredentialType credentialType,
                       RestrictionType restriction, Instant validFrom, Instant validTo, State state,
                       String acsCredentialId, String qrPayload, Instant issuedAt) {
        this.id = id;
        this.visitorId = visitorId;
        this.passTypeId = passTypeId;
        this.credentialType = credentialType;
        this.restriction = restriction;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.state = state;
        this.acsCredentialId = acsCredentialId;
        this.qrPayload = qrPayload;
        this.issuedAt = issuedAt;
    }

    /** A credential VMS intends to obtain. Written first, so nothing is issued off the books. */
    public static Credential requested(UUID visitorId, UUID passTypeId, CredentialType type,
                                       RestrictionType restriction, Instant validFrom,
                                       Instant validTo) {
        if (visitorId == null || type == null || restriction == null) {
            throw new IllegalArgumentException("a credential needs a visitor, a type and a restriction");
        }
        if (validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
            // The same invariant the table's CHECK enforces, refused before it reaches the database.
            throw new InvalidValidityWindow(validFrom, validTo);
        }
        return new Credential(UUID.randomUUID(), visitorId, passTypeId, type, restriction,
                validFrom, validTo, State.REQUESTED, null, null, null);
    }

    public static Credential stored(UUID id, UUID visitorId, UUID passTypeId, CredentialType type,
                                    RestrictionType restriction, Instant validFrom, Instant validTo,
                                    State state, String acsCredentialId, String qrPayload,
                                    Instant issuedAt) {
        return new Credential(id, visitorId, passTypeId, type, restriction, validFrom, validTo,
                state, acsCredentialId, qrPayload, issuedAt);
    }

    /** ACS answered. The reference is what makes this a real pass rather than an intention. */
    public Credential issued(String acsCredentialId, String qrPayload, Instant issuedAt) {
        if (state != State.REQUESTED) {
            throw new NotAwaitingIssuance(state);
        }
        if (acsCredentialId == null || acsCredentialId.isBlank()) {
            throw new IllegalArgumentException("an issued credential must carry its ACS reference");
        }
        return new Credential(id, visitorId, passTypeId, credentialType, restriction, validFrom,
                validTo, State.ACTIVE, acsCredentialId, qrPayload, issuedAt);
    }

    /** ACS refused in a way that will not change. Terminal — nothing retries a failed credential. */
    public Credential failed() {
        if (state != State.REQUESTED) {
            throw new NotAwaitingIssuance(state);
        }
        return new Credential(id, visitorId, passTypeId, credentialType, restriction, validFrom,
                validTo, State.FAILED, acsCredentialId, qrPayload, issuedAt);
    }

    public UUID id() {
        return id;
    }

    public UUID visitorId() {
        return visitorId;
    }

    public UUID passTypeId() {
        return passTypeId;
    }

    public CredentialType credentialType() {
        return credentialType;
    }

    public RestrictionType restriction() {
        return restriction;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validTo() {
        return validTo;
    }

    public State state() {
        return state;
    }

    public String acsCredentialId() {
        return acsCredentialId;
    }

    /** The scannable secret. Stored, returned to the pass renderer, and never logged. */
    public String qrPayload() {
        return qrPayload;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    /** Identifies the credential without disclosing what would open a barrier. */
    @Override
    public String toString() {
        return "Credential[" + id + " " + state.dbValue()
                + " qr=" + (qrPayload == null ? "none" : "present") + "]";
    }

    // ---- failures ----

    public static final class InvalidValidityWindow extends IllegalArgumentException {
        public InvalidValidityWindow(Instant from, Instant to) {
            super("A credential must expire after it begins (from=" + from + ", to=" + to + ")");
        }
    }

    public static final class NotAwaitingIssuance extends IllegalStateException {
        public final State state;

        public NotAwaitingIssuance(State state) {
            super("Credential is " + state.dbValue() + ", not awaiting issuance");
            this.state = state;
        }
    }
}
