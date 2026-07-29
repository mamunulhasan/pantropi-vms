package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.identity.usecase.MasterAdminPolicy;
import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.application.masterdata.usecase.ReceptionAdministration;
import com.pantropi.vms.domain.masterdata.MasterDataText;
import com.pantropi.vms.domain.masterdata.Reception;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Reception point API (US-04.4.1) — FR-CFG-05 (TDD-derived), consumed by FR-ADM-01 (SRS B1) and
 * FR-ADM-02 (SRS B1).
 *
 * <p>Reading needs {@code masterdata.view}; every mutation needs {@code masterdata.edit}. No DELETE:
 * users reference receptions and {@code vms.receptions.floor_id} is {@code ON DELETE RESTRICT}.
 *
 * <p>Two operations do not go through the shared pattern, because neither is a single-row edit:
 * transferring the central designation, and deactivating.
 */
@RestController
@RequestMapping("/api/v1/admin/receptions")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class ReceptionController {

    private final MasterDataAdministration<Reception> receptions;
    private final ReceptionAdministration administration;

    public ReceptionController(MasterDataAdministration<Reception> receptions,
                               ReceptionAdministration administration) {
        this.receptions = receptions;
        this.administration = administration;
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody ReceptionRequest req) {
        if (req == null || req.floorId() == null) {
            throw new MasterDataText.InvalidField("floorId", "is required");
        }
        // A reception is never created central. The designation is singular and transferring it
        // needs confirmation, so it is granted by its own operation rather than smuggled in on a
        // create where nobody would be asked (AC-2).
        UUID id = receptions.create(UUID.fromString(actor.userId()),
                Reception.draft(req.floorId(), req.code(), req.name(), false));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @RequiresPermission("masterdata.edit")
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id, @RequestBody ReceptionRequest req) {
        Reception existing = receptions.get(id);
        receptions.update(UUID.fromString(actor.userId()), id,
                new Reception(id, existing.floorId(), req.code(), req.name(), existing.central(),
                        existing.active()));
        return ResponseEntity.noContent().build();
    }

    /**
     * Move the central designation here (AC-2).
     *
     * @param confirm must be true when another reception already holds it — the response to a
     *                missing confirmation names the current holder so the caller can show it
     */
    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/designate-central")
    public ResponseEntity<Void> designateCentral(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean confirm) {
        administration.designateCentral(UUID.fromString(actor.userId()), id, confirm);
        return ResponseEntity.noContent().build();
    }

    /** Users stationed here, for the confirmation prompt before deactivating (AC-5). */
    @GetMapping("/{id}/dependents")
    public DependentsResponse dependents(@PathVariable UUID id) {
        return new DependentsResponse(administration.stationedUsers(id));
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/deactivate")
    public DependentsResponse deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        return new DependentsResponse(administration.deactivate(UUID.fromString(actor.userId()), id));
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        receptions.reactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ReceptionResponse get(@PathVariable UUID id) {
        return ReceptionResponse.of(receptions.get(id));
    }

    /**
     * Filter by floor, central flag and active status. The total is always present so an
     * administrator can check the 120 + 1 structure at a glance (AC-3).
     */
    @GetMapping
    public MasterDataStore.Page<ReceptionResponse> list(
            @RequestParam(required = false) UUID floorId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort) {
        MasterDataStore.Page<Reception> found = receptions.list(
                new MasterDataStore.Query(floorId, search, active, page, size, sort));
        return new MasterDataStore.Page<>(
                found.items().stream().map(ReceptionResponse::of).toList(),
                found.page(), found.size(), found.total());
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

    /** 409 with the current holder named, so the caller can ask and retry with confirm=true. */
    @ExceptionHandler(ReceptionAdministration.TransferNotConfirmed.class)
    public ResponseEntity<ConfirmationRequired> onConfirmationRequired(
            ReceptionAdministration.TransferNotConfirmed e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ConfirmationRequired(
                "confirmation_required", e.currentHolderId.toString(), e.currentHolderCode,
                e.getMessage()));
    }

    /** 409, not 403: the request is permitted, the system state is what forbids it right now. */
    @ExceptionHandler(MasterAdminPolicy.WouldStrandMasterAdmin.class)
    public ResponseEntity<ErrorResponse> onWouldStrand(
            MasterAdminPolicy.WouldStrandMasterAdmin e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("would_strand_master_admin", "isCentral", e.getMessage()));
    }

    @ExceptionHandler(MasterDataText.InvalidField.class)
    public ResponseEntity<ErrorResponse> onInvalidField(MasterDataText.InvalidField e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    public record ReceptionRequest(UUID floorId, String code, String name) {}

    public record ReceptionResponse(String id, String floorId, String code, String name,
                                    boolean central, boolean active) {
        static ReceptionResponse of(Reception r) {
            return new ReceptionResponse(r.id() == null ? null : r.id().toString(),
                    r.floorId().toString(), r.code(), r.name(), r.central(), r.active());
        }
    }

    public record DependentsResponse(int activeUsers) {}
    public record ConfirmationRequired(String error, String currentHolderId,
                                       String currentHolderCode, String message) {}
    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field, String message) {}
}
