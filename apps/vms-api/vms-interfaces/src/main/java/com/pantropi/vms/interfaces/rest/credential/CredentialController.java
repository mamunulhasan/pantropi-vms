package com.pantropi.vms.interfaces.rest.credential;

import com.pantropi.vms.application.acs.port.AcsFailure;
import com.pantropi.vms.application.credential.usecase.IssueCredential;
import com.pantropi.vms.domain.credential.Credential;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Credential issuance (US-09.1.1, T-09.1.1.5).
 *
 * <p>Guarded by {@code credential.issue}, which only MASTER_ADMIN holds — the central desk issues
 * passes, and AC-8 requires everyone else to be refused before any work happens.
 *
 * <p>The response never carries {@code qr_payload}. Rendering the pass is US-09.5.1, whose AC-3
 * says generation happens server-side and the raw payload never reaches the browser as a separate
 * value. Returning it here would hand out the secret through a side door the pass endpoint was
 * designed to close.
 */
@RestController
@RequestMapping("/api/v1/visitors/{visitorId}/credential")
@RequiresPermission("credential.issue")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class CredentialController {

    private final IssueCredential issueCredential;

    public CredentialController(IssueCredential issueCredential) {
        this.issueCredential = issueCredential;
    }

    @PostMapping
    public ResponseEntity<IssuedResponse> issue(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID visitorId, @RequestBody(required = false) IssueRequest req) {
        if (req == null || req.validFrom() == null || req.validTo() == null) {
            return ResponseEntity.badRequest().build();
        }
        Credential issued = issueCredential.issue(UUID.fromString(principal.userId()),
                new IssueCredential.Command(visitorId, req.passTypeId(),
                        CredentialType.parse(req.credentialType()),
                        RestrictionType.parse(req.restriction()),
                        req.validFrom(), req.validTo()));

        return ResponseEntity.status(HttpStatus.CREATED).body(new IssuedResponse(
                issued.id().toString(), issued.state().dbValue(), issued.acsCredentialId(),
                issued.issuedAt()));
    }

    /** Refused before any outbound call, so the unique index is never the first line of defence. */
    @ExceptionHandler(IssueCredential.AlreadyIssued.class)
    public ResponseEntity<ErrorResponse> onAlreadyIssued(IssueCredential.AlreadyIssued e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("already_issued",
                "This visitor already holds a live credential", e.existingCredentialId.toString()));
    }

    /**
     * ACS could not be reached, but the credential is recorded and will be retried.
     *
     * <p>503 rather than 500: the request was correct, the dependency was not available, and the
     * distinction is what tells a caller to come back rather than to change something.
     */
    @ExceptionHandler(AcsFailure.Transient.class)
    public ResponseEntity<ErrorResponse> onTransient() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ErrorResponse(
                "acs_unavailable",
                "The access control system did not respond. The credential is recorded and will be retried.",
                null));
    }

    /**
     * ACS refused, or answered unintelligibly. 502: the failure is downstream, not in the request.
     *
     * <p>The ACS message is deliberately not forwarded — no vendor error code crosses the boundary
     * (ADR-0002 decision 2), and a caller can do nothing with it but paste it into a ticket.
     */
    @ExceptionHandler(AcsFailure.class)
    public ResponseEntity<ErrorResponse> onAcsFailure() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ErrorResponse(
                "acs_refused", "The access control system refused the credential", null));
    }

    @ExceptionHandler(Credential.InvalidValidityWindow.class)
    public ResponseEntity<ErrorResponse> onWindow() {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_window", "A credential must expire after it begins", null));
    }

    /** @param credentialType {@code qr} or {@code rfid}; {@code restriction} time_bound|one_time */
    public record IssueRequest(UUID passTypeId, String credentialType, String restriction,
                               Instant validFrom, Instant validTo) {}

    /** No {@code qrPayload}: the pass endpoint of US-09.5.1 owns that, server-side. */
    public record IssuedResponse(String id, String state, String acsCredentialId, Instant issuedAt) {}

    public record ErrorResponse(String error, String detail, String existingCredentialId) {}
}
