package com.pantropi.vms.interfaces.rest.visitor;

import com.pantropi.vms.application.visitor.port.HostRepository;
import com.pantropi.vms.application.visitor.usecase.HostDirectory;
import com.pantropi.vms.domain.visitor.Host;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The host directory (US-10.1.1) — the people in a tenant organisation who receive visitors.
 *
 * <p>Guarded by {@code visitor.request}, the permission the TENANT role holds, because AC-1 makes
 * this a tenant maintaining its <em>own</em> directory: the tenant is derived from the acting
 * user's record, never from the path or the body, so there is no id a caller could supply to
 * reach another organisation's staff. A dedicated {@code host.manage} permission would say this
 * more precisely, and belongs with the FR-USR-02 decision that TODO-01 holds open rather than
 * being invented here.
 *
 * <p><strong>There is no delete route</strong>, and its absence is the mechanism rather than an
 * oversight: a host is named by every visit they ever received, so removing the row would orphan
 * the audit story of each one (AC-5). Departure is {@code /deactivate}, which leaves those
 * references readable (AC-3).
 *
 * <p>This response carries host email and phone, which the search endpoint of US-10.1.2
 * deliberately will not. The distinction is who is asking: here a tenant reads its own staff
 * directory in order to maintain it, whereas a search exists only to attach an id to a visit and
 * has no need of contact details.
 */
@RestController
@RequestMapping("/api/v1/hosts")
@RequiresPermission("visitor.request")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class HostController {

    private final HostDirectory hosts;

    public HostController(HostDirectory hosts) {
        this.hosts = hosts;
    }

    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @RequestBody HostRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID id = hosts.create(UUID.fromString(principal.userId()), req.fullName(), req.email(),
                req.phone());
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @GetMapping
    public HostPage list(@RequestParam(required = false) String search,
                         @RequestParam(required = false) Boolean active,
                         @RequestParam(defaultValue = "0") int page,
                         @RequestParam(defaultValue = "20") int size) {
        HostRepository.Page result = hosts.list(search, active, page, size);
        return new HostPage(result.content().stream().map(HostController::toResponse).toList(),
                result.totalElements(), result.page(), result.size(),
                HostRepository.Query.MAX_SIZE);
    }

    @GetMapping("/{id}")
    public HostResponse get(@PathVariable UUID id) {
        return toResponse(hosts.get(id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id, @RequestBody HostRequest req) {
        if (req == null) {
            return ResponseEntity.badRequest().build();
        }
        hosts.update(UUID.fromString(principal.userId()), id, req.fullName(), req.email(),
                req.phone());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id) {
        hosts.deactivate(UUID.fromString(principal.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID id) {
        hosts.reactivate(UUID.fromString(principal.userId()), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Unknown and another tenant's are the same answer, with an empty body (AC-4).
     *
     * <p>Anything else would make this endpoint a way to test whether a given uuid is somebody's
     * host — which is the enumeration the scoping exists to prevent.
     */
    @ExceptionHandler(HostDirectory.HostNotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    /** The field name, never the value: the value is the personal data (US-07.1.1 precedent). */
    @ExceptionHandler(Visitor.InvalidVisitorDetail.class)
    public ResponseEntity<ErrorResponse> onInvalid(Visitor.InvalidVisitorDetail e) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid", e.field()));
    }

    /**
     * The caller belongs to no tenant, so there is no directory for them to maintain.
     *
     * <p>409 rather than 400: the request was well formed, and it is the account's assignment —
     * not the payload — that would have to change for it to succeed.
     */
    @ExceptionHandler(HostDirectory.NoTenantForUser.class)
    public ResponseEntity<ErrorResponse> onNoTenant() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("no_tenant",
                        "Your account is not assigned to a tenant, so it has no host directory"));
    }

    public record HostRequest(String fullName, String email, String phone) {}

    public record CreatedResponse(String id) {}

    public record HostResponse(String id, String fullName, String email, String phone,
                               boolean active) {}

    public record HostPage(List<HostResponse> content, long totalElements, int page, int size,
                           int maxSize) {}

    public record ErrorResponse(String error, String detail) {}

    private static HostResponse toResponse(Host host) {
        return new HostResponse(host.id().toString(), host.fullName(), host.emailValue(),
                host.phoneValue(), host.active());
    }
}
