package com.pantropi.vms.infrastructure.credential;

import com.pantropi.vms.application.acs.port.AcsFailure;
import com.pantropi.vms.application.credential.usecase.IssueCredential;
import com.pantropi.vms.application.visitor.port.CredentialIssuance;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;

import java.time.Instant;
import java.util.UUID;

/**
 * Connects an approval to credential issuance (US-09.1.2), without either side learning about the
 * other.
 *
 * <p>It is deliberately thin, and it deliberately names no ACS type. What an ACS failure <em>means</em>
 * has already been decided one layer down, in {@link IssueCredential}: a transient failure leaves
 * the credential {@code requested} for the retry machinery, a permanent one marks it {@code failed}.
 * Re-deriving that here would be a second opinion on a question already answered, and the two would
 * eventually disagree.
 *
 * <p>What it does read from the failure is which of the two states was written: a
 * {@link AcsFailure.Transient} left the credential {@code requested} and a retry will pick it up, so
 * the approver is told the pass is coming; anything else marked it {@code failed}, and they are told
 * it is not. That distinction is the difference between an accurate report and a reassuring one.
 *
 * <p>Depending on the port's failure type is not a boundary breach: {@code application.acs.port} is
 * one of the two sanctioned homes, and no wire-format type or vendor-named class crosses it
 * (CON-01, CON-02, ADR-0002). The visitor context on the other side of {@link CredentialIssuance}
 * still sees nothing but an {@code Outcome}.
 */
public final class IssueCredentialBridge implements CredentialIssuance {

    private final IssueCredential issueCredential;

    public IssueCredentialBridge(IssueCredential issueCredential) {
        this.issueCredential = issueCredential;
    }

    @Override
    public Outcome issueOnApproval(UUID actor, UUID visitorId, Instant validFrom, Instant validTo) {
        try {
            // QR and time_bound: the approved window is the credential's window, and a pass a
            // visitor shows on a phone is the only kind this deployment can actually produce.
            // passTypeId is null because nothing on a request or a visitor carries one — only
            // vms.credentials has the column, and only a manual caller ever supplies it.
            issueCredential.issue(actor, new IssueCredential.Command(visitorId, null,
                    CredentialType.QR, RestrictionType.TIME_BOUND, validFrom, validTo));
            return Outcome.ISSUED;
        } catch (IssueCredential.AlreadyIssued e) {
            // Not a failure. The visitor has a working pass, which is the outcome that was wanted.
            return Outcome.ALREADY_HELD;
        } catch (AcsFailure.Transient e) {
            // Left as `requested` by IssueCredential — recorded, and what the retry looks for.
            return Outcome.DEFERRED;
        } catch (RuntimeException e) {
            // Permanent, malformed, or something nobody anticipated. The credential is `failed` or
            // was never written; either way this visitor has no pass and will not get one by waiting.
            return Outcome.FAILED;
        }
    }
}
