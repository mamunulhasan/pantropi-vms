package com.pantropi.vms.application.acs.port;

import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;

import java.time.Instant;
import java.util.UUID;

/**
 * The outbound boundary to the Access Control System (US-11.1.1, ADR-0002 decision 1).
 *
 * <p>Every credential operation VMS performs leaves through here, and every parameter and return
 * type below is a VMS type. That is the whole point: the UAL contract does not exist yet
 * (TODO-02), so an interface shaped around a guess at it would bake the guess into the callers.
 * Shaped around our own vocabulary instead, the wire adapter can be written — or rewritten — when
 * the contract lands, and nothing above this line changes.
 *
 * <p><strong>No HTTP client, no serialisation library, no vendor package</strong> appears here or
 * anywhere it can reach (AC-3). {@code AcsBoundaryRulesTest} fails the build on a violation, and
 * as of this story that rule stops passing vacuously — the package finally has something in it.
 *
 * <p>Inbound traffic — the access, exit and card events ACS pushes at us — is
 * {@link AcsEventInbox}, not more methods here. Two directions with two sets of implementors:
 * this one is implemented by the adapter and called by VMS, that one is implemented by VMS and
 * called by the adapter. Merging them would make every implementor of either stub half of it.
 *
 * <p>Reliability lives in the implementation, never in the caller (ADR-0002 decision 5): retry,
 * backoff, the durable {@code vms.acs_requests} outbox and dead-lettering are the adapter's
 * business. A caller sees an {@link AcsFailure} or a result.
 */
public interface AcsPort {

    /**
     * Ask ACS to create a credential for a visitor.
     *
     * @throws AcsFailure.Transient        the call may succeed if repeated
     * @throws AcsFailure.Permanent        it will not, and retrying is pointless
     * @throws AcsFailure.MalformedResponse ACS answered, unintelligibly
     */
    AcsCredential createCredential(AcsCredentialRequest request);

    /** Withdraw a credential. Idempotent by contract: deactivating a dead credential is not an error. */
    void deactivateCredential(AcsCredentialReference reference);

    /** What ACS currently believes about a credential — which may disagree with what VMS stored. */
    AcsCredentialStatus queryCredentialStatus(AcsCredentialReference reference);

    /**
     * What VMS asks for, in the columns {@code vms.credentials} actually has (AC-2).
     *
     * <p>Deliberately not modelled on any ACS field naming. There is none to model on, and if
     * there were, borrowing it here is exactly the leak the anti-corruption layer exists to stop.
     *
     * @param visitorId the visitor the credential is for — never a name, email or phone: ACS has
     *                  no need of visitor personal data to mint a credential
     */
    record AcsCredentialRequest(UUID visitorId, CredentialType credentialType,
                                RestrictionType restriction, Instant validFrom, Instant validTo) {

        public AcsCredentialRequest {
            if (visitorId == null || credentialType == null || restriction == null) {
                throw new IllegalArgumentException("a credential request needs a visitor, a type and a restriction");
            }
            if (validFrom == null || validTo == null || !validTo.isAfter(validFrom)) {
                // The same invariant vms.credentials enforces with a CHECK. Refusing here means an
                // impossible window never reaches the wire, let alone the database.
                throw new IllegalArgumentException("a credential must expire after it begins");
            }
        }
    }

    /**
     * The opaque handle ACS gives back, stored as {@code vms.credentials.acs_credential_id}.
     *
     * <p>A wrapper rather than a bare String so it cannot be confused at a call site with the QR
     * payload, which is also a String and also opaque — and which is the one that must never be
     * logged.
     */
    record AcsCredentialReference(String value) {

        public AcsCredentialReference {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("an ACS credential reference cannot be blank");
            }
        }
    }

    /**
     * What ACS returns on success.
     *
     * @param qrPayload the scannable content for a {@code qr} credential, null for {@code rfid}.
     *                  This is the secret: it grants entry, so it is stored and never logged.
     */
    record AcsCredential(AcsCredentialReference reference, String qrPayload, Instant issuedAt) {

        public AcsCredential {
            if (reference == null || issuedAt == null) {
                throw new IllegalArgumentException("an issued credential needs a reference and a time");
            }
        }

        /** Identifies the credential without disclosing what would open a barrier. */
        @Override
        public String toString() {
            return "AcsCredential[" + reference.value() + " qr=" + (qrPayload == null ? "none" : "present") + "]";
        }
    }

    /**
     * ACS's own view of a credential, in VMS words.
     *
     * <p>{@code UNKNOWN} is a real answer, not an error: ACS may have no record of a reference VMS
     * holds — after a restore, or a credential created and lost mid-flight — and a caller
     * reconciling the two needs to be told that rather than handed an exception.
     */
    enum AcsCredentialStatus {
        ACTIVE,
        EXPIRED,
        REVOKED,
        UNKNOWN
    }
}
