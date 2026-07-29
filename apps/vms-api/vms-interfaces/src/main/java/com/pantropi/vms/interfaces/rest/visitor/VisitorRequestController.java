package com.pantropi.vms.interfaces.rest.visitor;

import com.pantropi.vms.application.visitor.usecase.ApproveVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.RequestTransitions;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visitor request submission and approval (US-07.1.1, US-07.4.1) — FR-VMS-01/02 (SRS B1).
 *
 * <p>{@code POST /api/v1/visitor-requests} is guarded by {@code visitor.request} at the class level
 * (US-03.2.1); {@code POST /{id}/approve} overrides that with {@code visitor.approve}, because
 * raising a request and deciding one are separate authorities held by separate roles.
 *
 * <p>The controller holds only HTTP mapping: it takes the acting user's id from the validated token
 * and passes commands with no field for tenant, status, approver or decision time, so none of those
 * can be mass-assigned from a body (AC-5).
 */
@RestController
@RequestMapping("/api/v1/visitor-requests")
@RequiresPermission("visitor.request")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class VisitorRequestController {

    private final SubmitVisitorRequest submitVisitorRequest;
    private final ApproveVisitorRequest approveVisitorRequest;

    public VisitorRequestController(SubmitVisitorRequest submitVisitorRequest,
                                    ApproveVisitorRequest approveVisitorRequest) {
        this.submitVisitorRequest = submitVisitorRequest;
        this.approveVisitorRequest = approveVisitorRequest;
    }

    @PostMapping
    public ResponseEntity<SubmittedResponse> submit(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @RequestBody SubmitRequest body) {

        if (body == null || body.visitors() == null || body.visitors().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        UUID id = submitVisitorRequest.submit(
                UUID.fromString(principal.userId()),     // AC-5: submitter from the token only
                new SubmitVisitorRequest.Command(
                        body.hostId(), body.scheduledFrom(), body.scheduledTo(), body.purpose(),
                        body.visitors().stream()
                                .map(v -> new SubmitVisitorRequest.VisitorDetail(
                                        v.fullName(), v.email(), v.phone(), v.company()))
                                .toList()));

        return ResponseEntity.created(URI.create("/api/v1/visitor-requests/" + id))
                .body(new SubmittedResponse(id.toString(), "submitted"));
    }

    /**
     * FM Admin approval (US-07.4.1, T-07.4.1.3) — FR-VMS-02 (SRS B1).
     *
     * <p>{@code visitor.approve}, not the class-level {@code visitor.request}: submitting a request
     * and deciding one are different authorities held by different roles, and a tenant who can raise
     * a request must not be able to grant it.
     *
     * <p>The approver comes from the validated token. The body carries only an optional note, so
     * there is nothing else a client could set — no status, no approver, no decision time.
     */
    @PostMapping("/{id}/approve")
    @RequiresPermission("visitor.approve")
    public ResponseEntity<DecisionResponse> approve(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) DecisionRequest body) {

        ApproveVisitorRequest.Decision decision = approveVisitorRequest.approve(
                UUID.fromString(principal.userId()), id, body == null ? null : body.note());

        return ResponseEntity.ok(new DecisionResponse(decision.requestId().toString(),
                decision.status(), decision.decidedBy().toString(), decision.decidedAt(),
                decision.note()));
    }

    /**
     * Already decided (AC-5), or decided by someone else first (AC-6).
     *
     * <p>Both are 409 and both name the state, because from the caller's side they are the same
     * situation: the request is no longer theirs to decide, and the fix is to look again.
     */
    @ExceptionHandler({RequestTransitions.IllegalTransition.class,
            ApproveVisitorRequest.DecidedElsewhere.class})
    public ResponseEntity<DecisionError> onAlreadyDecided(RuntimeException e) {
        String state = e instanceof RequestTransitions.IllegalTransition t
                ? t.from.dbValue() : "decided";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DecisionError("already_decided", "The request is " + state
                        + " and can no longer be approved"));
    }

    /**
     * The request was well-formed and the state was right; the world had moved on (AC-7). 422 rather
     * than 409 because retrying will never succeed — the window cannot become un-elapsed.
     */
    @ExceptionHandler(VisitorRequest.WindowAlreadyElapsed.class)
    public ResponseEntity<DecisionError> onElapsedWindow(VisitorRequest.WindowAlreadyElapsed e) {
        return ResponseEntity.unprocessableEntity()
                .body(new DecisionError("window_elapsed", e.getMessage()));
    }

    @ExceptionHandler(ApproveVisitorRequest.RequestNotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(VisitorRequest.DecisionReasonTooLong.class)
    public ResponseEntity<DecisionError> onNoteTooLong(VisitorRequest.DecisionReasonTooLong e) {
        return ResponseEntity.badRequest().body(new DecisionError("invalid", e.getMessage()));
    }

    /** Optional note only — everything else about a decision is server-determined. */
    public record DecisionRequest(String note) {}

    public record DecisionResponse(String id, String status, String decidedBy, Instant decidedAt,
                                   String note) {}

    public record DecisionError(String error, String detail) {}

    /**
     * Submission payload. Note what is <em>absent</em>: no {@code tenantId}, {@code status},
     * {@code requestedBy} or {@code approvedBy}. Those are server-determined; a client sending them
     * has them silently ignored because there is nowhere for them to bind (AC-5).
     */
    public record SubmitRequest(UUID hostId, Instant scheduledFrom, Instant scheduledTo,
                                String purpose, List<VisitorPayload> visitors) {}

    public record VisitorPayload(String fullName, String email, String phone, String company) {}

    public record SubmittedResponse(String id, String status) {}
}
