package com.pantropi.vms.interfaces.rest.credential;

import com.pantropi.vms.application.acs.port.AcsFailure;
import com.pantropi.vms.application.credential.usecase.GetCredentialPass;
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
 * <p>The issuance response never carries {@code qr_payload}. Rendering the pass is US-09.5.1,
 * whose AC-3 says generation happens server-side and the raw payload never reaches the browser as
 * a separate value — and the portal issues from the browser, so putting it in this response would
 * hand out the secret through a side door.
 *
 * <p>{@code GET .../credential} does return it, and is meant for exactly one caller: the portal
 * <em>server</em>, which renders a PNG the browser can show. That split is a design rather than an
 * enforcement — the route is reachable by anyone holding {@code credential.issue} — and the gap is
 * recorded as deferred rather than described as closed.
 */
@RestController
@RequestMapping("/api/v1/visitors/{visitorId}/credential")
@RequiresPermission("credential.issue")
// Gated on the ACS mode as well as identity: this controller needs IssueCredential, which needs an
// AcsPort, which exists only when an ACS is configured. Without this, every profile with identity
// on and no ACS fails to start on a missing bean — no route is better than no application.
@ConditionalOnProperty(prefix = "vms.acs", name = "mode")
public class CredentialController {

    private final IssueCredential issueCredential;
    private final GetCredentialPass getCredentialPass;

    public CredentialController(IssueCredential issueCredential, GetCredentialPass getCredentialPass) {
        this.issueCredential = issueCredential;
        this.getCredentialPass = getCredentialPass;
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

    /**
     * The live pass, payload included, for the portal server's renderer (US-09.5.1).
     *
     * <p>{@code Cache-Control: no-store} because the body is the thing that opens a barrier: a
     * proxy or browser cache holding it would outlive the validity window it is supposed to respect.
     */
    @GetMapping
    public ResponseEntity<PassResponse> pass(@PathVariable UUID visitorId) {
        GetCredentialPass.Pass p = getCredentialPass.forVisitor(visitorId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(new PassResponse(p.credentialId().toString(), p.qrPayload(), p.validFrom(),
                        p.validTo(), p.issuedAt()));
    }

    @ExceptionHandler(GetCredentialPass.NoPass.class)
    public ResponseEntity<ErrorResponse> onNoPass() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("no_credential", "This visitor holds no pass", null));
    }

    /** 409, not 404: the credential exists, it just is not in a state that has a pass to show. */
    @ExceptionHandler(GetCredentialPass.NotRenderable.class)
    public ResponseEntity<ErrorResponse> onNotRenderable(GetCredentialPass.NotRenderable e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("not_issued",
                "The pass is " + e.state.dbValue() + " and cannot be shown yet", null));
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

    /** No {@code qrPayload}: the GET above owns that, and only the portal server should read it. */
    public record IssuedResponse(String id, String state, String acsCredentialId, Instant issuedAt) {}

    /** Server-to-server. {@code qrPayload} must not be forwarded to a browser as a value. */
    public record PassResponse(String credentialId, String qrPayload, Instant validFrom,
                               Instant validTo, Instant issuedAt) {}

    public record ErrorResponse(String error, String detail, String existingCredentialId) {}
}
