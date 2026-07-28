package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.domain.masterdata.Building;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Building register API (US-04.1.1) — FR-CFG-02 (TDD-derived).
 *
 * <p>Reading needs {@code masterdata.view}; every mutation needs {@code masterdata.edit},
 * declared per method so the read default cannot accidentally cover a write (AC-6).
 *
 * <p><strong>There is no DELETE mapping</strong>, and that is the design rather than an omission:
 * {@code vms.floors.building_id} is {@code ON DELETE RESTRICT} and historical records must stay
 * resolvable, so retirement is deactivation (AC-5). The verb is absent all the way down — controller,
 * use case and port — so it cannot be reached by any route.
 *
 * <p>The controller holds no logic beyond HTTP mapping.
 */
@RestController
@RequestMapping("/api/v1/admin/buildings")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class BuildingController {

    private final MasterDataAdministration<Building> buildings;

    public BuildingController(MasterDataAdministration<Building> buildings) {
        this.buildings = buildings;
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody BuildingRequest req) {
        UUID id = buildings.create(UUID.fromString(actor.userId()),
                Building.draft(req.code(), req.name(), req.address()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @RequiresPermission("masterdata.edit")
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id, @RequestBody BuildingRequest req) {
        buildings.update(UUID.fromString(actor.userId()), id,
                Building.draft(req.code(), req.name(), req.address()));
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        buildings.deactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        buildings.reactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public Building get(@PathVariable UUID id) {
        return buildings.get(id);
    }

    @GetMapping
    public MasterDataStore.Page<Building> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort) {
        return buildings.list(new MasterDataStore.Query(search, active, page, size, sort));
    }

    @ExceptionHandler(MasterDataStore.DuplicateCode.class)
    public ResponseEntity<ErrorResponse> onDuplicate(MasterDataStore.DuplicateCode e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("duplicate", "code", e.getMessage()));
    }

    @ExceptionHandler(MasterDataStore.NotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    /** The domain record rejects its own bad input; this turns that into a 400 naming the field. */
    @ExceptionHandler(com.pantropi.vms.domain.masterdata.MasterDataText.InvalidField.class)
    public ResponseEntity<ErrorResponse> onInvalidField(
            com.pantropi.vms.domain.masterdata.MasterDataText.InvalidField e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    public record BuildingRequest(String code, String name, String address) {}
    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field, String message) {}
}
