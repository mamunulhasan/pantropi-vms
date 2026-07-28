package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.domain.masterdata.Floor;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Floor API, nested under its building (US-04.2.1) — FR-CFG-03 (TDD-derived).
 *
 * <p>Every route carries the building id, because a floor code only means something inside one:
 * "L01" identifies a floor when you already know the tower and nothing at all when you do not
 * (AC-4). Taking the parent from the path rather than the body also means the two can never
 * disagree — there is no body field for a client to contradict it with.
 *
 * <p>Reading needs {@code masterdata.view}; every mutation needs {@code masterdata.edit}.
 * <strong>No DELETE mapping</strong>: {@code vms.receptions.floor_id} is {@code ON DELETE RESTRICT}
 * and retirement is deactivation (AC-6).
 */
@RestController
@RequestMapping("/api/v1/admin/buildings/{buildingId}/floors")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class FloorController {

    private final MasterDataAdministration<Floor> floors;

    public FloorController(MasterDataAdministration<Floor> floors) {
        this.floors = floors;
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID buildingId, @RequestBody FloorRequest req) {
        UUID id = floors.create(UUID.fromString(actor.userId()),
                Floor.draft(buildingId, req.code(), req.name(), req.levelNo()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @RequiresPermission("masterdata.edit")
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID buildingId, @PathVariable UUID id, @RequestBody FloorRequest req) {
        floors.update(UUID.fromString(actor.userId()), id,
                Floor.draft(buildingId, req.code(), req.name(), req.levelNo()));
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID buildingId, @PathVariable UUID id) {
        floors.deactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID buildingId, @PathVariable UUID id) {
        floors.reactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public Floor get(@PathVariable UUID buildingId, @PathVariable UUID id) {
        Floor floor = floors.get(id);
        if (!floor.buildingId().equals(buildingId)) {
            // Reached through the wrong building, so as far as this route is concerned it is not
            // there. Reporting it as found would let the path be used to enumerate floors.
            throw new MasterDataStore.NotFound();
        }
        return floor;
    }

    @GetMapping
    public MasterDataStore.Page<Floor> list(
            @PathVariable UUID buildingId,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "levelNo") String sort) {
        return floors.list(new MasterDataStore.Query(buildingId, search, active, page, size, sort));
    }

    @ExceptionHandler(MasterDataStore.DuplicateCode.class)
    public ResponseEntity<ErrorResponse> onDuplicate(MasterDataStore.DuplicateCode e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("duplicate", "code", e.getMessage()));
    }

    /** AC-5: an absent or deactivated building is a validation failure naming the field. */
    @ExceptionHandler(MasterDataStore.InvalidParent.class)
    public ResponseEntity<ErrorResponse> onInvalidParent(MasterDataStore.InvalidParent e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    @ExceptionHandler(MasterDataStore.NotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(com.pantropi.vms.domain.masterdata.MasterDataText.InvalidField.class)
    public ResponseEntity<ErrorResponse> onInvalidField(
            com.pantropi.vms.domain.masterdata.MasterDataText.InvalidField e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    public record FloorRequest(String code, String name, Integer levelNo) {}
    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field, String message) {}
}
