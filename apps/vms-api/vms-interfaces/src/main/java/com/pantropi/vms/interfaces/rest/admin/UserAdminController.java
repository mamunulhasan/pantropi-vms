package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.identity.port.UserAdministrationStore.NewUser;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.Page;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.UserFilter;
import com.pantropi.vms.application.identity.usecase.UserAdministration;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * User administration API (US-02.2.1) — FR-ADM-02 (SRS B1). Guarded by the {@code user.manage}
 * permission in {@link AdminAuthorizationInterceptor}; the controller assumes an authorised
 * principal and holds only HTTP mapping.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiresPermission("user.manage")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class UserAdminController {

    private final UserAdministration users;

    public UserAdminController(UserAdministration users) {
        this.users = users;
    }

    @PostMapping
    public ResponseEntity<CreatedResponse> create(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody CreateRequest req) {
        UUID id = users.create(UUID.fromString(actor.userId()),
                new NewUser(req.username(), req.email(), req.fullName(), req.roleCode(),
                        req.receptionId(), req.tenantId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id, @RequestBody UpdateRequest req) {
        users.updateAssignment(UUID.fromString(actor.userId()), id, req.roleCode(),
                req.receptionId(), req.tenantId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/deactivate")
    public ResponseEntity<Void> deactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor, @PathVariable UUID id) {
        users.deactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reactivate")
    public ResponseEntity<Void> reactivate(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor, @PathVariable UUID id) {
        users.reactivate(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public Page list(@RequestParam(required = false) String role,
                     @RequestParam(required = false) UUID receptionId,
                     @RequestParam(required = false) Boolean active,
                     @RequestParam(defaultValue = "0") int page,
                     @RequestParam(defaultValue = "20") int size,
                     @RequestParam(defaultValue = "username") String sort) {
        return users.list(new UserFilter(role, receptionId, active, page, size, sort));
    }

    // ---- uniform 400s for validation failures ----
    @ExceptionHandler(UserAdministration.DuplicateField.class)
    public ResponseEntity<ErrorResponse> onDuplicate(UserAdministration.DuplicateField e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("duplicate", e.field));
    }

    @ExceptionHandler({UserAdministration.InvalidField.class, UserAdministration.InvalidReference.class})
    public ResponseEntity<ErrorResponse> onInvalid(RuntimeException e) {
        String field = e instanceof UserAdministration.InvalidField f ? f.field
                : ((UserAdministration.InvalidReference) e).field;
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid", field));
    }

    @ExceptionHandler(UserAdministration.UserNotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    public record CreateRequest(String username, String email, String fullName, String roleCode,
                                UUID receptionId, UUID tenantId) {}
    public record UpdateRequest(String roleCode, UUID receptionId, UUID tenantId) {}
    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field) {}
}
