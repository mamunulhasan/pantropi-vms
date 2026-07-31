package com.pantropi.vms.infrastructure.acs;

import com.pantropi.vms.application.acs.port.AcsFailure;
import com.pantropi.vms.application.acs.port.AcsPort;
import com.pantropi.vms.domain.masterdata.CredentialType;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory Access Control System (US-11.2.1, ADR-0002 decision 3).
 *
 * <p>ADR-0002 asks for a simulator rather than a stub, and the difference is behavioural: a stub
 * returns whatever makes the caller proceed, while this keeps state and enforces the rules a real
 * ACS would. Deactivating a credential twice is accepted and does not resurrect it; querying one
 * that was never issued answers {@code UNKNOWN} rather than throwing; a credential whose window
 * has passed reports {@code EXPIRED} without anyone telling it to. Code written against this
 * therefore meets a system that says no, which is the only kind worth testing against.
 *
 * <p><strong>What it cannot do is prove anything about UAL.</strong> TODO-02 is open, the contract
 * is unpublished, and every story that touches this closes at "done against simulator" and never
 * at "verified" (ADR-0002, "Honest limitation"). A green run here means our side is internally
 * consistent. It means nothing else.
 *
 * <p>The QR payload is a random opaque token. A real ACS mints something meaningful to its
 * readers; what matters to VMS is that the value is opaque, unguessable and stored rather than
 * derived — so a payload that is merely random exercises every caller correctly, and guarantees
 * no code accidentally depends on its shape.
 *
 * <p>Latency and failure injection are US-11.2.2 and are deliberately absent here: this story is
 * the baseline, and a simulator that fails randomly before anything is built against it makes the
 * first failures impossible to attribute.
 */
public final class AcsSimulator implements AcsPort {

    /** What the simulated ACS remembers, which is not the same as what VMS stored. */
    private record Issued(AcsCredentialRequest request, Instant issuedAt, boolean revoked) {}

    private final Map<String, Issued> credentials = new ConcurrentHashMap<>();
    private final Clock clock;

    public AcsSimulator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AcsCredential createCredential(AcsCredentialRequest request) {
        if (request == null) {
            throw new AcsFailure.Permanent("no credential request supplied");
        }
        Instant now = clock.instant();
        if (!request.validTo().isAfter(now)) {
            // A real ACS has no use for a credential that is already dead, and accepting one here
            // would let a caller believe it had issued something usable.
            throw new AcsFailure.Permanent("the requested validity window has already ended");
        }

        String reference = "SIM-" + UUID.randomUUID();
        credentials.put(reference, new Issued(request, now, false));

        // Only a qr credential carries a payload; an rfid card is a physical object collected at
        // reception, and inventing a payload for it would give callers something to render.
        String qrPayload = request.credentialType() == CredentialType.QR
                ? "SIMQR." + UUID.randomUUID().toString().replace("-", "")
                : null;

        return new AcsCredential(new AcsCredentialReference(reference), qrPayload, now);
    }

    @Override
    public void deactivateCredential(AcsCredentialReference reference) {
        if (reference == null) {
            throw new AcsFailure.Permanent("no credential reference supplied");
        }
        // Idempotent by contract: an unknown or already-revoked reference is not an error. A
        // revocation that failed because it had already happened would be retried forever.
        credentials.computeIfPresent(reference.value(),
                (key, issued) -> new Issued(issued.request(), issued.issuedAt(), true));
    }

    @Override
    public AcsCredentialStatus queryCredentialStatus(AcsCredentialReference reference) {
        if (reference == null) {
            throw new AcsFailure.Permanent("no credential reference supplied");
        }
        Issued issued = credentials.get(reference.value());
        if (issued == null) {
            // A reference VMS holds that ACS does not know: a real answer, and the one
            // reconciliation needs to hear.
            return AcsCredentialStatus.UNKNOWN;
        }
        if (issued.revoked()) {
            return AcsCredentialStatus.REVOKED;
        }
        // Expiry is a fact about the clock, not a state anyone has to remember to write.
        return issued.request().validTo().isAfter(clock.instant())
                ? AcsCredentialStatus.ACTIVE
                : AcsCredentialStatus.EXPIRED;
    }

    /** How many credentials the simulated ACS is holding — for tests and local diagnosis only. */
    public int issuedCount() {
        return credentials.size();
    }
}
