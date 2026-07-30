package com.pantropi.vms.interfaces.rest.visitor;

import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;
import com.pantropi.vms.application.visitor.port.DecisionTrail;
import com.pantropi.vms.application.visitor.port.VisitorRequestQueries;
import com.pantropi.vms.application.visitor.usecase.MyVisitorRequests;
import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.usecase.AmendVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.ApproveVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.CancelVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.PendingApprovals;
import com.pantropi.vms.application.visitor.usecase.RejectVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.RequestDecision;
import com.pantropi.vms.application.visitor.usecase.RequestHistory;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.RequestTransitions;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.AuthorizationDenialRecorder;
import jakarta.servlet.http.HttpServletRequest;
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
    private final AmendVisitorRequest amendVisitorRequest;
    private final CancelVisitorRequest cancelVisitorRequest;
    private final AuthorizationDenialRecorder denials;
    private final RequestHistory requestHistory;

    public VisitorRequestController(SubmitVisitorRequest submitVisitorRequest,
                                    ApproveVisitorRequest approveVisitorRequest,
                                    RejectVisitorRequest rejectVisitorRequest,
                                    PendingApprovals pendingApprovals,
                                    MyVisitorRequests myVisitorRequests,
                                    AmendVisitorRequest amendVisitorRequest,
                                    CancelVisitorRequest cancelVisitorRequest,
                                    AuthorizationDenialRecorder denials,
                                    RequestHistory requestHistory) {
        this.submitVisitorRequest = submitVisitorRequest;
        this.approveVisitorRequest = approveVisitorRequest;
        this.rejectVisitorRequest = rejectVisitorRequest;
        this.pendingApprovals = pendingApprovals;
        this.myVisitorRequests = myVisitorRequests;
        this.amendVisitorRequest = amendVisitorRequest;
        this.cancelVisitorRequest = cancelVisitorRequest;
        this.denials = denials;
        this.requestHistory = requestHistory;
    }

    /**
     * The decision trail for one request (US-07.4.3, T-07.4.3.3, AC-4) — FR-VMS-02 (SRS B1).
     *
     * <p>Guarded by {@code audit.view}, which only SYSTEM_ADMIN holds — including <em>not</em> the
     * FM Admins who take the decisions. Reading back who decided what is an oversight function, not
     * part of taking a decision (AC-6).
     *
     * <p>Chronological, because it is read to reconstruct a sequence months later and a sequence
     * reads forwards. The entries carry no visitor personal data: what an audit row may contain is
     * decided by {@code AuditProjection}, not here.
     */
    @GetMapping("/{id}/history")
    @RequiresPermission("audit.view")
    public List<HistoryEntry> history(@PathVariable UUID id) {
        return requestHistory.of(id).stream()
                .map(e -> new HistoryEntry(e.at(), e.action(), e.actor(),
                        e.beforeState(), e.afterState()))
                .toList();
    }

    /**
     * Amend a request still awaiting a decision (US-07.1.3, T-07.1.3.3) — FR-VMS-01 (SRS B1).
     *
     * <p>{@code PATCH}, and the body means it: an absent field is left alone rather than cleared.
     * Under the class-level {@code visitor.request}, and object-level authorisation comes from the
     * tenant-scoped repository read rather than from a check written here — another tenant's id is
     * simply not found (AC-6).
     */
    @PatchMapping("/{id}")
    public ResponseEntity<DecisionResponse> amend(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id,
            @RequestBody AmendRequest body) {

        if (body == null) {
            return ResponseEntity.badRequest().build();
        }

        RequestDecision result = amendVisitorRequest.amend(
                UUID.fromString(principal.userId()), id,
                new AmendVisitorRequest.Amendment(body.hostId(), body.scheduledFrom(),
                        body.scheduledTo(), body.purpose(),
                        body.visitors() == null ? null : body.visitors().stream()
                                .map(v -> new SubmitVisitorRequest.VisitorDetail(
                                        v.fullName(), v.email(), v.phone(), v.company(),
                                        v.visitorTypeId()))
                                .toList()));

        return ResponseEntity.ok(new DecisionResponse(result.requestId().toString(),
                result.status(), result.decidedBy().toString(), result.decidedAt(), null));
    }

    /**
     * Withdraw a request (US-07.1.3, T-07.1.3.3, AC-2/AC-3) — FR-VMS-01 (SRS B1).
     *
     * <p>Legal from {@code submitted} and from {@code approved}: a plan changes after approval more
     * often than before it, and the alternative is a visitor nobody expects arriving at the gate.
     * Cancelling an approved request signals credential revocation downstream.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<DecisionResponse> cancel(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id) {

        RequestDecision result = cancelVisitorRequest.cancel(
                UUID.fromString(principal.userId()), id);

        return ResponseEntity.ok(new DecisionResponse(result.requestId().toString(),
                result.status(), result.decidedBy().toString(), result.decidedAt(), null));
    }

    /** Amending a decided request: 409 naming the state it is in (AC-4). */
    @ExceptionHandler(VisitorRequest.RequestNotPending.class)
    public ResponseEntity<DecisionError> onNotPending(VisitorRequest.RequestNotPending e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new DecisionError("already_decided", e.getMessage()));
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
     *
     * <h2>Two callers, one resource (US-07.3.3, T-07.3.3.2)</h2>
     * The tenant who raised the request reads it under {@code visitor.request}; the FM Admin
     * deciding it reads it under {@code visitor.approve}. Either permission opens the route — and
     * <em>what</em> each of them gets is still the scoping policy's decision at the query, so the
     * approver sees any request in the building and the tenant sees only their own, from the same
     * handler and with no branch here that could get it the wrong way round.
     *
     * <p>Reading this is audited (AC-2): it is the endpoint that names people.
     *
     * <h2>A GET that writes, and why that is not a CSRF hole here</h2>
     * CodeQL flags this handler under {@code java/csrf-unprotected-request-type}, and the observation
     * behind the flag is correct and worth keeping in view: a {@code GET} now has a side effect,
     * because AC-2 requires the act of reading to be recorded.
     *
     * <p>It is not exploitable as CSRF in this API. Cross-site request forgery needs an
     * <em>ambient</em> credential — one the victim's browser attaches by itself. This system has
     * none: authentication is a bearer token read from the {@code Authorization} header
     * ({@code AuthorizationInterceptor}), there is no cookie anywhere in the interfaces or
     * infrastructure layers, and a browser will not add that header to a cross-origin request. A
     * forged request therefore arrives unauthenticated and is refused before this method runs.
     *
     * <p>What the side effect can do at worst is add a row saying somebody looked — attributable to
     * whoever's token was used, and only ever by someone who already holds one. That is the audit
     * trail working, not being subverted.
     *
     * <p>If cookie-based sessions are ever introduced, this reasoning stops holding and this handler
     * is one of the places that has to be revisited. That is the reason it is written down here
     * rather than dismissed in a dashboard where the next reader will not find it.
     */
    @GetMapping("/{id}")
    @RequiresPermission({"visitor.request", "visitor.approve"})
    public MyRequestDetail myRequest(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id) {
        VisitorRequestQueries.Detail d =
                myVisitorRequests.detail(UUID.fromString(principal.userId()), id);
        return new MyRequestDetail(d.id().toString(), d.status(), d.host(), d.purpose(),
                d.scheduledFrom(), d.scheduledTo(), d.submittedAt(), d.decidedBy(), d.decidedAt(),
                d.decisionReason(),
                d.visitors().stream()
                        .map(v -> new VisitorLine(v.fullName(), v.company(), v.visitorType(),
                                v.status()))
                        .toList());
    }

    /**
     * A malformed visitor detail or an unusable visitor type (US-07.1.2 AC-3, AC-4).
     *
     * <p>The response names the field and never echoes the value — the exception types carry the
     * field name for exactly this reason, so a bad email cannot be reflected back into a response,
     * a log line or an error-reporting payload.
     */
    @ExceptionHandler({Visitor.InvalidVisitorDetail.class,
            SubmitVisitorRequest.UnknownVisitorType.class})
    public ResponseEntity<DecisionError> onInvalidVisitor(RuntimeException e) {
        String field = e instanceof Visitor.InvalidVisitorDetail d ? d.field() : "visitorTypeId";
        return ResponseEntity.badRequest().body(new DecisionError("invalid", field));
    }

    /**
     * A filter we do not understand is refused, never quietly dropped (US-07.6.1 AC-2,
     * T-07.3.2.2).
     *
     * <p>The message names what was expected but not what was sent, so a filter value cannot be
     * reflected back into a response or an access log.
     */
    @ExceptionHandler({MyVisitorRequests.UnknownStatus.class, PendingApprovals.UnknownStatus.class,
            PendingApprovals.InvalidDateRange.class, PendingApprovals.SearchTooLong.class})
    public ResponseEntity<DecisionError> onInvalidFilter(RuntimeException e) {
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
     *
     * <h2>Filters (US-07.3.2)</h2>
     * {@code status}, {@code tenantId}, {@code from}/{@code to} over the visit window, and
     * {@code search} over visitor and host names. All optional, all conjunctive, and all applied
     * <em>after</em> the scope predicate — a filter narrows what the caller may already see and can
     * never widen it (AC-5).
     *
     * <p>{@code status} defaults to {@code submitted}. A visitor name is searchable and is still
     * never returned: the queue lists counts, not people.
     */
    @GetMapping("/pending")
    @RequiresPermission("visitor.approve")
    public PendingPage pending(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        ApprovalQueueStore.Page result =
                pendingApprovals.list(status, tenantId, from, to, search, page, size);

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
                                        v.fullName(), v.email(), v.phone(), v.company(),
                                        v.visitorTypeId()))
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

    /**
     * Not there, or not the caller's (US-07.1.3 AC-6).
     *
     * <p>404 rather than 403, so the response cannot confirm that some id belongs to another
     * tenant — and precisely because it says nothing, the attempt is recorded. Someone walking
     * identifiers to find what else is in the building leaves a trail even though every answer they
     * get is identical.
     *
     * <p>A mistyped id is recorded too. That is the honest consequence of refusing to distinguish
     * the two cases: the server cannot tell them apart either, and choosing to audit only the
     * cross-tenant ones would mean knowing which they were.
     */
    @ExceptionHandler(RequestDecision.NotFound.class)
    public ResponseEntity<Void> onNotFound(
            HttpServletRequest request,
            @RequestAttribute(name = AuthenticatedPrincipal.ATTRIBUTE, required = false)
            AuthenticatedPrincipal principal) {

        denials.record(request, 404, "visitor_request.object",
                principal == null ? null : UUID.fromString(principal.userId()));
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

    public record VisitorLine(String fullName, String company, String visitorType,
                             String status) {}

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

    /**
     * A partial edit. An absent field is left as it is — this is a {@code PATCH}, not a replacement.
     *
     * <p>No {@code tenantId}, {@code status} or {@code approvedBy}, exactly as on submission: a
     * client must not be able to amend its way to an approval.
     */
    public record AmendRequest(UUID hostId, Instant scheduledFrom, Instant scheduledTo,
                               String purpose, List<VisitorPayload> visitors) {}

    /**
     * One entry of the trail.
     *
     * <p>{@code actor} is a display name, and null when the account has since been removed — the
     * entry outlives the account, which is the point of an immutable trail. The state projections
     * are returned as raw JSON strings because that is exactly what was recorded; re-shaping them
     * here would make the endpoint show something other than what the trail says.
     */
    public record HistoryEntry(Instant at, String action, String actor, String beforeState,
                               String afterState) {}

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

    public record VisitorPayload(String fullName, String email, String phone, String company,
                                UUID visitorTypeId) {}

    public record SubmittedResponse(String id, String status) {}
}
