package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.domain.masterdata.MasterDataText;
import com.pantropi.vms.domain.masterdata.VisitorType;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Visitor type API (US-04.5.1) — FR-CFG-06 (TDD-derived).
 *
 * <p>Reading needs {@code masterdata.view}; every mutation needs {@code masterdata.edit}.
 *
 * <p><strong>No DELETE mapping</strong>, and here that is a choice rather than a constraint:
 * {@code vms.visitors.visitor_type_id} is {@code ON DELETE SET NULL}, so the database would permit
 * a delete and silently blank the classification of every visitor recorded under that type (AC-5).
 * A retired type is deactivated, so history keeps meaning what it meant.
 */
@RestController
@RequestMapping("/api/v1/admin/visitor-types")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class VisitorTypeController {

    private final MasterDataAdministration<VisitorType> visitorTypes;

    public VisitorTypeController(MasterDataAdministration<VisitorType> visitorTypes) {
        this.visitorTypes = visitorTypes;
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody VisitorTypeRequest req) {
        UUID id = visitorTypes.create(UUID.fromString(actor.userId()),
                VisitorType.draft(req.code(), req.name(), req.description()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @RequiresPermission("masterdata.edit")
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id, @RequestBody VisitorTypeRequest req) {
        visitorTypes.update(UUID.fromString(actor.userId()), id,
                VisitorType.draft(req.code(), req.name(), req.description()));
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        visitorTypes.deactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        visitorTypes.reactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public VisitorType get(@PathVariable UUID id) {
        return visitorTypes.get(id);
    }

    @GetMapping
    public MasterDataStore.Page<VisitorType> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort) {
        // Null parent: visitor types are a flat list, not nested under anything.
        return visitorTypes.list(new MasterDataStore.Query(null, search, active, page, size, sort));
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

    @ExceptionHandler(MasterDataText.InvalidField.class)
    public ResponseEntity<ErrorResponse> onInvalidField(MasterDataText.InvalidField e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    public record VisitorTypeRequest(String code, String name, String description) {}
    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field, String message) {}
}
