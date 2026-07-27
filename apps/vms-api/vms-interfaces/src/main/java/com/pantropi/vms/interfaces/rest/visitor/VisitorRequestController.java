package com.pantropi.vms.interfaces.rest.visitor;

import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Visitor request submission (US-07.1.1, T-07.1.1.4) — FR-VMS-01 (SRS B1).
 *
 * <p>{@code POST /api/v1/visitor-requests}, guarded by the {@code visitor.request} permission at the
 * API boundary (US-03.2.1). The controller holds only HTTP mapping: it takes the submitter's id from
 * the validated token and passes a command that has no field for tenant, status or approver, so
 * those cannot be mass-assigned from the body (AC-5).
 */
@RestController
@RequestMapping("/api/v1/visitor-requests")
@RequiresPermission("visitor.request")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class VisitorRequestController {

    private final SubmitVisitorRequest submitVisitorRequest;

    public VisitorRequestController(SubmitVisitorRequest submitVisitorRequest) {
        this.submitVisitorRequest = submitVisitorRequest;
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
     * Submission payload. Note what is <em>absent</em>: no {@code tenantId}, {@code status},
     * {@code requestedBy} or {@code approvedBy}. Those are server-determined; a client sending them
     * has them silently ignored because there is nowhere for them to bind (AC-5).
     */
    public record SubmitRequest(UUID hostId, Instant scheduledFrom, Instant scheduledTo,
                                String purpose, List<VisitorPayload> visitors) {}

    public record VisitorPayload(String fullName, String email, String phone, String company) {}

    public record SubmittedResponse(String id, String status) {}
}
