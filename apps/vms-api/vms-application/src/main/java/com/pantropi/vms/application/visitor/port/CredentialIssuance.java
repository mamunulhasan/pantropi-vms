package com.pantropi.vms.application.visitor.port;

import java.time.Instant;
import java.util.UUID;

/**
 * How the visitor context asks for a pass to be minted (US-09.1.2) — FR-VMS-05 (SRS B1).
 *
 * <p>A port for the same reason as {@link IssuedCredentials}: credentials are the credential
 * context's data, and the visitor context asks rather than reaches in. Nothing about ACS appears in
 * this signature — an approval knows it wants a visitor to have a pass for a window, and knows
 * nothing about what is on the other end of the wire.
 *
 * <p><strong>This method must not throw.</strong> It is called after the approval has already been
 * committed, so an exception could not roll anything back; it could only turn a successful approval
 * into an error response for the FM who took it. Every failure has a name in {@link Outcome}
 * instead.
 */
public interface CredentialIssuance {

    /**
     * @param actor     the approving user — recorded as the cause, because they are the cause
     * @param validFrom the approved window's start; the credential is not valid before it
     * @return what happened, per visitor — never null, never an exception
     */
    Outcome issueOnApproval(UUID actor, UUID visitorId, Instant validFrom, Instant validTo);

    /**
     * The five things that can come of asking.
     *
     * <p>{@code DEFERRED} and {@code FAILED} are not the same and must not be collapsed: a deferred
     * credential is recorded and waiting for a retry, while a failed one will never become a pass
     * without somebody doing something. Telling an approver "it is coming" when it is not is the
     * failure this distinction exists to prevent.
     */
    enum Outcome {
        /** The pass exists and is active. */
        ISSUED,
        /** The visitor already held a live credential; nothing was minted and nothing is wrong. */
        ALREADY_HELD,
        /** Recorded as requested; the access control system did not answer and it will be retried. */
        DEFERRED,
        /** Refused in a way retrying will not fix. This visitor has no pass and will not get one. */
        FAILED,
        /** Automatic issuance is switched off, or no access control system is configured. */
        SKIPPED
    }
}
