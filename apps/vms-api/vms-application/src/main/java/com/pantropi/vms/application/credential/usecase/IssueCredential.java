package com.pantropi.vms.application.credential.usecase;

import com.pantropi.vms.application.acs.port.AcsFailure;
import com.pantropi.vms.application.acs.port.AcsPort;
import com.pantropi.vms.application.credential.port.CredentialRepository;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.notification.usecase.SendCredentialEmail;
import com.pantropi.vms.domain.credential.Credential;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Ask ACS for a visitor's credential (US-09.1.1).
 *
 * <p><strong>The order of operations is the design.</strong> The row is written {@code requested}
 * before anything leaves the building (AC-1), the duplicate check happens before that (AC-7), and
 * the outcome is written afterwards (AC-3). A call that succeeded while no row existed would be a
 * pass opening a door that VMS cannot account for; a duplicate discovered by the unique index
 * would be discovered <em>after</em> ACS had already minted the second one.
 *
 * <p>The three failure kinds are not interchangeable, and the difference decides what state the
 * credential is left in:
 *
 * <ul>
 *   <li>{@code Transient} — left {@code requested}, because that is what the retry machinery
 *       (US-11.3.1) will come back for. Marking it failed would abandon a pass that was a timeout
 *       away from working.</li>
 *   <li>{@code Permanent} and {@code MalformedResponse} — marked {@code failed}, because retrying
 *       turns one bad request into a queue of them.</li>
 * </ul>
 *
 * <p>No write here is transactional with the ACS call, and it cannot be: an external system does
 * not join our transaction. The row-first ordering is what makes that survivable — a crash between
 * the two leaves a {@code requested} credential, which is recoverable, rather than a silent one.
 *
 * <p>The visitor is emailed last, after the credential is already active, and the send cannot
 * fail this method: {@link SendCredentialEmail} reports its outcome instead of throwing, because a
 * mail server being down is not a reason to leave a visitor without a pass (US-17.1.1 AC-6).
 *
 * <p>Pure orchestration over ports; no framework, and no ACS type anywhere (AC-2).
 */
public final class IssueCredential {

    private final CredentialRepository credentials;
    private final AcsPort acs;
    private final AuditTrail audit;
    private final SendCredentialEmail sendEmail;

    public IssueCredential(CredentialRepository credentials, AcsPort acs, AuditTrail audit,
                           SendCredentialEmail sendEmail) {
        this.credentials = credentials;
        this.acs = acs;
        this.audit = audit;
        this.sendEmail = sendEmail;
    }

    /**
     * @return the credential, {@code active} on success
     * @throws AlreadyIssued when the visitor already holds a live one — before any outbound call
     */
    public Credential issue(UUID actingUser, Command command) {
        credentials.findLiveFor(command.visitorId()).ifPresent(existing -> {
            throw new AlreadyIssued(command.visitorId(), existing.id());
        });

        Credential requested = Credential.requested(command.visitorId(), command.passTypeId(),
                command.credentialType(), command.restriction(), command.validFrom(),
                command.validTo());
        credentials.save(requested);
        audit.recordChange(actingUser, "credential.requested", "credential",
                requested.id().toString(), null,
                "{\"visitorId\":\"" + command.visitorId() + "\",\"type\":\""
                        + command.credentialType().databaseValue() + "\"}");

        AcsPort.AcsCredential answer;
        try {
            answer = acs.createCredential(new AcsPort.AcsCredentialRequest(command.visitorId(),
                    command.credentialType(), command.restriction(), command.validFrom(),
                    command.validTo()));
        } catch (AcsFailure.Transient e) {
            // Left requested on purpose — see the class comment. Nothing is marked failed here.
            audit.recordChange(actingUser, "credential.request_deferred", "credential",
                    requested.id().toString(), null, "{\"retryable\":true}");
            throw e;
        } catch (AcsFailure e) {
            Credential failed = requested.failed();
            credentials.update(failed);
            audit.recordChange(actingUser, "credential.request_failed", "credential",
                    requested.id().toString(), null, "{\"retryable\":false}");
            throw e;
        }

        Credential active = requested.issued(answer.reference().value(), answer.qrPayload(),
                answer.issuedAt());
        credentials.update(active);
        // The reference identifies the credential; the payload opens the barrier. Only one of them
        // belongs in an audit row.
        audit.recordChange(actingUser, "credential.issued", "credential", active.id().toString(),
                null, "{\"acsCredentialId\":\"" + active.acsCredentialId() + "\",\"hasQr\":"
                        + (active.qrPayload() != null) + "}");

        // Last, and unable to fail the issuance. The outcome is audited so a pass that was issued
        // but not emailed is visible rather than silently assumed to have been delivered.
        SendCredentialEmail.Result mail = sendEmail.send(new SendCredentialEmail.Command(
                command.visitorId(), active.id(), command.validFrom(), command.validTo()));
        audit.recordChange(actingUser, "credential.notified", "credential", active.id().toString(),
                null, "{\"outcome\":\"" + mail.outcome() + "\"}");
        return active;
    }

    /**
     * @param passTypeId the pass type the window and kind came from, kept for provenance; nullable
     *                   because a credential may be issued before pass types are configured
     */
    public record Command(UUID visitorId, UUID passTypeId, CredentialType credentialType,
                          RestrictionType restriction, Instant validFrom, Instant validTo) {}

    /** The visitor already holds a requested or active credential (AC-7). */
    public static final class AlreadyIssued extends RuntimeException {
        public final UUID existingCredentialId;

        public AlreadyIssued(UUID visitorId, UUID existingCredentialId) {
            super("Visitor " + visitorId + " already holds a live credential");
            this.existingCredentialId = existingCredentialId;
        }
    }
}
