package com.pantropi.vms.interfaces.rest.visitor;

import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;
import com.pantropi.vms.application.visitor.port.VisitorRequestQueries;
import com.pantropi.vms.application.visitor.usecase.MyVisitorRequests;
import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.usecase.ApproveVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.PendingApprovals;
import com.pantropi.vms.application.visitor.usecase.RejectVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.RequestDecision;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.RequestTransitions;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visitor requests: submission, decisions, the approval queue and the tenant's own list
 * (US-07.1.1, US-07.3.1, US-07.4.1, US-07.4.2, US-07.6.1) — FR-VMS-01/02 (SRS B1).
 *
 * <p>{@code POST /api/v1/visitor-requests} is guarded by {@code visitor.request} at the class level
 * (US-03.2.1); {@code POST /{id}/approve} and {@code POST /{id}/reject} override that with
 * {@code visitor.approve}, because raising a request and deciding one are separate authorities held
 * by separate roles.
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
    private final RejectVisitorRequest rejectVisitorRequest;
    private final PendingApprovals pendingApprovals;
    private final MyVisitorRequests myVisitorRequests;

    public VisitorRequestController(SubmitVisitorRequest submitVisitorRequest,
                                    ApproveVisitorRequest approveVisitorRequest,
                                    RejectVisitorRequest rejectVisitorRequest,
                                    PendingApprovals pendingApprovals,
                                    MyVisitorRequests myVisitorRequests) {
        this.submitVisitorRequest = submitVisitorRequest;
        this.approveVisitorRequest = approveVisitorRequest;
        this.rejectVisitorRequest = rejectVisitorRequest;
        this.pendingApprovals = pendingApprovals;
        this.myVisitorRequests = myVisitorRequests;
    }

    /**
     * The tenant's own requests and their status (US-07.6.1, T-07.6.1.2) — FR-VMS-01 (SRS B1).
     *
     * <p>Guarded by the class-level {@code visitor.request}: raising requests and seeing what became
     * of them are the same tenant-facing capability.
     *
     * <p>Which rows come back is <strong>not</strong> decided here. This method has no tenant
     * parameter and builds no condition; the adapter takes the predicate from {@code ScopePolicy},
     * so the filters below narrow the tenant's own set and cannot widen it (AC-2).
     *
     * @param status optional; an unrecognised value is a 400 rather than an ignored filter
     * @param from   optional lower bound on the visit window, inclusive
     * @param to     optional upper bound on the visit window, exclusive
     */
    @GetMapping
    public MyRequestsPage myRequests(
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        VisitorRequestQueries.Page result = myVisitorRequests.list(status, from, to, page, size);

        return new MyRequestsPage(
                result.content().stream()
                        .map(r -> new MyRequestRow(r.id().toString(), r.status(), r.host(),
                                r.scheduledFrom(), r.scheduledTo(), r.visitorCount(),
                                r.submittedAt(), r.decidedAt(), r.decisionReason()))
                        .toList(),
                result.totalElements(), result.page(), result.size(), PageRequest.MAX_SIZE);
    }

    /**
     * One request in full, including the decision and why (US-07.6.1 AC-3).
     *
     * <p>A request belonging to another tenant answers <strong>404</strong>, identical to one that
     * never existed (AC-4). It is one scoped query, so there is no ownership branch that could take
     * measurably longer than a miss, and nothing for this method to phrase differently.
     *
     * <p>The rejection reason is returned as a JSON string. It is stored exactly as the approver
     * typed it (US-07.4.2 AC-6); encoding it for display is the renderer's job, and the JSON
     * serialiser escapes it for this transport.
     */
    @GetMapping("/{id}")
    public MyRequestDetail myRequest(@PathVariable UUID id) {
        VisitorRequestQueries.Detail d = myVisitorRequests.detail(id);
        return new MyRequestDetail(d.id().toString(), d.status(), d.host(), d.purpose(),
                d.scheduledFrom(), d.scheduledTo(), d.submittedAt(), d.decidedBy(), d.decidedAt(),
                d.decisionReason(),
                d.visitors().stream()
                        .map(v -> new VisitorLine(v.fullName(), v.status())).toList());
    }

    /** AC-2: a filter we do not understand is refused, never quietly dropped. */
    @ExceptionHandler(MyVisitorRequests.UnknownStatus.class)
    public ResponseEntity<DecisionError> onUnknownStatus(MyVisitorRequests.UnknownStatus e) {
        return ResponseEntity.badRequest().body(new DecisionError("invalid", e.getMessage()));
    }

    /**
     * The approval queue (US-07.3.1, T-07.3.1.3) — FR-VMS-02 (SRS B1).
     *
     * <p>{@code GET /api/v1/visitor-requests/pending}, guarded by {@code visitor.approve}. AC-4 is
     * explicit that the dashboard is never merely hidden from the navigation: a Tenant calling this
     * directly gets 403 and an audited denial, exactly as for the decision endpoints.
     *
     * <p><strong>Maximum page size is {@value PageRequest#MAX_SIZE}.</strong> A larger request is
     * clamped rather than refused, and the applied size comes back on the response so a caller can
     * see what it actually got (AC-5).
     *
     * <p>The row shape carries no visitor contact detail — see {@link ApprovalQueueStore}; it is
     * never selected, rather than selected and then dropped here.
     */
    @GetMapping("/pending")
    @RequiresPermission("visitor.approve")
    public PendingPage pending(@RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "20") int size) {

        ApprovalQueueStore.Page result = pendingApprovals.list(page, size);

        return new PendingPage(
                result.content().stream()
                        .map(r -> new PendingRow(r.id().toString(), r.tenantName(), r.hostName(),
                                r.scheduledFrom(), r.scheduledTo(), r.visitorCount(),
                                r.submittedAt()))
                        .toList(),
                result.totalElements(), result.page(), result.size(), PageRequest.MAX_SIZE);
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

        RequestDecision decision = approveVisitorRequest.approve(
                UUID.fromString(principal.userId()), id, body == null ? null : body.note());

        return ResponseEntity.ok(new DecisionResponse(decision.requestId().toString(),
                decision.status(), decision.decidedBy().toString(), decision.decidedAt(),
                decision.note()));
    }

    /**
     * FM Admin rejection (US-07.4.2, T-07.4.2.3) — FR-VMS-02 (SRS B1).
     *
     * <p>Same authority as approval: {@code visitor.approve} is the permission to <em>decide</em>,
     * and the grant matrix has no separate one for refusing.
     *
     * <p>The reason is mandatory and is enforced by the aggregate, not here — AC-2 asks for 400 from
     * a direct API call that bypasses the UI, and a check written in this method would be one a
     * future bulk-rejection endpoint would have to remember to repeat.
     */
    @PostMapping("/{id}/reject")
    @RequiresPermission("visitor.approve")
    public ResponseEntity<DecisionResponse> reject(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @RequestBody(required = false) RejectRequest body) {

        RequestDecision decision = rejectVisitorRequest.reject(
                UUID.fromString(principal.userId()), id, body == null ? null : body.reason());

        return ResponseEntity.ok(new DecisionResponse(decision.requestId().toString(),
                decision.status(), decision.decidedBy().toString(), decision.decidedAt(),
                decision.note()));
    }

    /** AC-2: a refusal nobody has to justify is not an accountable decision. */
    @ExceptionHandler(VisitorRequest.RejectionReasonRequired.class)
    public ResponseEntity<DecisionError> onMissingReason() {
        return ResponseEntity.badRequest()
                .body(new DecisionError("reason_required", "A rejection must say why"));
    }

    /**
     * Already decided (AC-5), or decided by someone else first (AC-6).
     *
     * <p>Both are 409 and both name the state, because from the caller's side they are the same
     * situation: the request is no longer theirs to decide, and the fix is to look again.
     */
    @ExceptionHandler({RequestTransitions.IllegalTransition.class,
            RequestDecision.DecidedElsewhere.class})
    public ResponseEntity<DecisionError> onAlreadyDecided(RuntimeException e) {
        String state = e instanceof RequestTransitions.IllegalTransition t
                ? t.from.dbValue() : "decided";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DecisionError("already_decided", "The request is " + state
                        + " and can no longer be decided"));
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

    @ExceptionHandler(RequestDecision.NotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(VisitorRequest.DecisionReasonTooLong.class)
    public ResponseEntity<DecisionError> onNoteTooLong(VisitorRequest.DecisionReasonTooLong e) {
        return ResponseEntity.badRequest().body(new DecisionError("invalid", e.getMessage()));
    }

    /** A row of the tenant's own list — status, window, host, count, and the decision (AC-1). */
    public record MyRequestRow(String id, String status, String host, Instant scheduledFrom,
                               Instant scheduledTo, int visitorCount, Instant submittedAt,
                               Instant decidedAt, String decisionReason) {}

    public record MyRequestsPage(List<MyRequestRow> content, long totalElements, int page, int size,
                                 int maxSize) {}

    /** @param decidedBy the approver's display name only — no username, email or id */
    public record MyRequestDetail(String id, String status, String host, String purpose,
                                  Instant scheduledFrom, Instant scheduledTo, Instant submittedAt,
                                  String decidedBy, Instant decidedAt, String decisionReason,
                                  List<VisitorLine> visitors) {}

    public record VisitorLine(String fullName, String status) {}

    /**
     * One queue row. Counts and names an approver needs to recognise the request — deliberately no
     * visitor email, phone or identity-document reference.
     */
    public record PendingRow(String id, String tenant, String host, Instant scheduledFrom,
                             Instant scheduledTo, int visitorCount, Instant submittedAt) {}

    /**
     * @param size    the size actually applied, which may be less than the one asked for (AC-5)
     * @param maxSize the server ceiling, so a client can stop asking for more than it can have
     */
    public record PendingPage(List<PendingRow> content, long totalElements, int page, int size,
                              int maxSize) {}

    /** Optional note only — everything else about a decision is server-determined. */
    public record DecisionRequest(String note) {}

    /** The reason is required; an absent body is the same as an absent reason, and both are 400. */
    public record RejectRequest(String reason) {}

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
