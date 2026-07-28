package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.MasterDataText;
import com.pantropi.vms.domain.masterdata.PassType;
import com.pantropi.vms.domain.masterdata.RestrictionType;
import com.pantropi.vms.domain.masterdata.UnknownDatabaseValue;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Pass type API (US-04.6.1) — FR-CFG-07 (TDD-derived), consumed by FR-VMS-11 (SRS B1) and
 * FR-VMS-13 (SRS B1) in Phase 2.
 *
 * <p>Enum fields are validated before anything reaches the database, and the rejection lists the
 * permitted values (AC-3) — "must be one of qr, rfid" rather than a constraint violation the caller
 * has to decode.
 *
 * <p>Reading needs {@code masterdata.view}; every mutation needs {@code masterdata.edit}. No DELETE:
 * existing credentials reference these rows, and retirement is deactivation (AC-6).
 */
@RestController
@RequestMapping("/api/v1/admin/pass-types")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class PassTypeController {

    private final MasterDataAdministration<PassType> passTypes;

    public PassTypeController(MasterDataAdministration<PassType> passTypes) {
        this.passTypes = passTypes;
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody PassTypeRequest req) {
        UUID id = passTypes.create(UUID.fromString(actor.userId()), toDraft(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @RequiresPermission("masterdata.edit")
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id, @RequestBody PassTypeRequest req) {
        passTypes.update(UUID.fromString(actor.userId()), id, toDraft(req));
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        passTypes.deactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        passTypes.reactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public PassTypeResponse get(@PathVariable UUID id) {
        return PassTypeResponse.of(passTypes.get(id));
    }

    @GetMapping
    public MasterDataStore.Page<PassTypeResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "code") String sort) {
        MasterDataStore.Page<PassType> found =
                passTypes.list(new MasterDataStore.Query(null, search, active, page, size, sort));
        return new MasterDataStore.Page<>(
                found.items().stream().map(PassTypeResponse::of).toList(),
                found.page(), found.size(), found.total());
    }

    /**
     * Parsing happens here rather than in the record so a bad enum is a 400 naming the field,
     * instead of Jackson failing to bind and producing a message about a Java type.
     */
    private static PassType toDraft(PassTypeRequest req) {
        return PassType.draft(req.code(), req.name(),
                CredentialType.parse(req.defaultCredential()),
                RestrictionType.parse(req.defaultRestriction()),
                req.defaultValidHours() == null ? 0 : req.defaultValidHours());
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

    /**
     * The database holds an enum value this build does not know. Not the caller's fault and not
     * fixable by changing the request, so it is a 500 — and the message stays in the log rather
     * than the response, per the standard error handling.
     */
    @ExceptionHandler(UnknownDatabaseValue.class)
    public ResponseEntity<ErrorResponse> onUnknownStoredValue(UnknownDatabaseValue e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("configuration_error", e.type,
                        "A stored value is not understood by this build; see the server log."));
    }

    public record PassTypeRequest(String code, String name, String defaultCredential,
                                  String defaultRestriction, Integer defaultValidHours) {}

    /** Enums are rendered as the database spells them, so the API and the schema agree. */
    public record PassTypeResponse(String id, String code, String name, String defaultCredential,
                                   String defaultRestriction, int defaultValidHours,
                                   boolean active) {
        static PassTypeResponse of(PassType p) {
            return new PassTypeResponse(p.id() == null ? null : p.id().toString(), p.code(),
                    p.name(), p.defaultCredential().databaseValue(),
                    p.defaultRestriction().databaseValue(), p.defaultValidHours(), p.active());
        }
    }

    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field, String message) {}
}
