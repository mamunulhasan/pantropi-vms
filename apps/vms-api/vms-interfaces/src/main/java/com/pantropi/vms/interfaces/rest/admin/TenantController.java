package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.port.TenantDependencies;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.domain.masterdata.MasterDataText;
import com.pantropi.vms.domain.masterdata.Tenant;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Tenant register API (US-04.3.1) — FR-CFG-04 (TDD-derived).
 *
 * <p>Reading needs {@code masterdata.view}; every mutation needs {@code masterdata.edit}. No DELETE:
 * users, hosts and visitor requests reference tenants, so retirement is deactivation.
 *
 * <p><strong>Contact details are personal data.</strong> They are returned to an authorised caller
 * and recorded in the audit payload, but no handler here writes them to a log, and
 * {@link Tenant#toString()} is overridden so that no future one can do it by accident (AC-6).
 */
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class TenantController {

    private final MasterDataAdministration<Tenant> tenants;
    private final TenantDependencies dependencies;

    public TenantController(MasterDataAdministration<Tenant> tenants,
                            TenantDependencies dependencies) {
        this.tenants = tenants;
        this.dependencies = dependencies;
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody TenantRequest req) {
        UUID id = tenants.create(UUID.fromString(actor.userId()), toDraft(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @RequiresPermission("masterdata.edit")
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id, @RequestBody TenantRequest req) {
        tenants.update(UUID.fromString(actor.userId()), id, toDraft(req));
        return ResponseEntity.noContent().build();
    }

    /**
     * What still depends on this tenant (AC-3). Meant to be read before confirming a deactivation,
     * so an administrator retiring a tenant with forty users attached knows before, not after.
     */
    @GetMapping("/{id}/dependents")
    public DependentsResponse dependents(@PathVariable UUID id) {
        tenants.get(id);   // 404 for an unknown tenant rather than a confident zero
        return new DependentsResponse(dependencies.activeUserCount(id));
    }

    /**
     * Deactivation is not blocked by dependants — the requirement is that the administrator is
     * told, and then decides. The response repeats the count so a caller that skipped
     * {@code /dependents} still learns what it just affected.
     */
    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/deactivate")
    public DependentsResponse deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        int affected = dependencies.activeUserCount(id);
        tenants.deactivate(UUID.fromString(actor.userId()), id);
        return new DependentsResponse(affected);
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        tenants.reactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable UUID id) {
        return TenantResponse.of(tenants.get(id));
    }

    @GetMapping
    public MasterDataStore.Page<TenantResponse> list(
            @RequestParam(required = false) UUID floorId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort) {
        MasterDataStore.Page<Tenant> found = tenants.list(
                new MasterDataStore.Query(floorId, search, active, page, size, sort));
        return new MasterDataStore.Page<>(
                found.items().stream().map(TenantResponse::of).toList(),
                found.page(), found.size(), found.total());
    }

    private static Tenant toDraft(TenantRequest req) {
        if (req == null) {
            throw new MasterDataText.InvalidField("code", "is required");
        }
        return Tenant.draft(req.code(), req.name(), req.floorId(), req.contactEmail(),
                req.contactPhone());
    }

    @ExceptionHandler(MasterDataStore.DuplicateCode.class)
    public ResponseEntity<ErrorResponse> onDuplicate(MasterDataStore.DuplicateCode e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("duplicate", "code", e.getMessage()));
    }

    @ExceptionHandler(MasterDataStore.InvalidParent.class)
    public ResponseEntity<ErrorResponse> onInvalidParent(MasterDataStore.InvalidParent e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    @ExceptionHandler(MasterDataStore.NotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    /**
     * The message names the field and describes the shape — it never echoes the submitted value,
     * because that value is an email address or a phone number and the response is one more place
     * it would then exist (AC-6).
     */
    @ExceptionHandler(MasterDataText.InvalidField.class)
    public ResponseEntity<ErrorResponse> onInvalidField(MasterDataText.InvalidField e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    public record TenantRequest(String code, String name, UUID floorId, String contactEmail,
                                String contactPhone) {}

    public record TenantResponse(String id, String code, String name, String floorId,
                                 String contactEmail, String contactPhone, boolean active) {
        static TenantResponse of(Tenant t) {
            return new TenantResponse(t.id() == null ? null : t.id().toString(), t.code(), t.name(),
                    t.floorId() == null ? null : t.floorId().toString(),
                    t.contactEmail(), t.contactPhone(), t.active());
        }
    }

    /** @param activeUsers users still assigned to this tenant at the moment of asking */
    public record DependentsResponse(int activeUsers) {}

    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field, String message) {}
}
