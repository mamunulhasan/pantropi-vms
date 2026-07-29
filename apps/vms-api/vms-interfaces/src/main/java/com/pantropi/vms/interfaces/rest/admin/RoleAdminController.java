package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.identity.usecase.RoleAdministration;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Role and grant administration (US-03.3.1) — FR-USR-02 (TDD-derived).
 *
 * <p>Guarded by {@code user.manage} at the class level, so every route here needs it (AC-6).
 *
 * <p><strong>There is no create-role and no create-permission route.</strong> TODO-01 leaves
 * FR-USR-02 undefined in the SRS, so the five roles and eleven permissions are the published
 * schema's and inventing more would be inventing requirements. The use case has no method for it
 * either, so this is not a route that was merely left out.
 *
 * <p>A grant change takes effect on an affected user's <em>next request</em> without them logging in
 * again (AC-3) — {@code AuthorizationInterceptor} resolves permissions per request, so there is
 * nothing to invalidate and nothing to wait for.
 */
@RestController
@RequestMapping("/api/v1/admin/roles")
@RequiresPermission("user.manage")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class RoleAdminController {

    private final RoleAdministration roles;

    public RoleAdminController(RoleAdministration roles) {
        this.roles = roles;
    }

    /** Roles, their grants and versions, active user counts, and the permission catalogue (AC-1). */
    @GetMapping
    public RoleAdministration.Overview overview() {
        return roles.overview();
    }

    @GetMapping("/{roleCode}")
    public RoleAdministration.RoleView role(@PathVariable String roleCode) {
        return roles.role(roleCode);
    }

    /**
     * Replace a role's grants (AC-2).
     *
     * <p>The whole set is submitted rather than a delta, because that is what the screen shows and
     * what the version token describes. Applying a delta computed against a stale read is exactly
     * the failure the token exists to catch.
     */
    @PutMapping("/{roleCode}/grants")
    public RoleAdministration.RoleView replaceGrants(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable String roleCode, @RequestBody GrantsRequest req) {
        Set<String> requested = req == null || req.permissions() == null
                ? Set.of() : new LinkedHashSet<>(req.permissions());
        return roles.replaceGrants(UUID.fromString(actor.userId()), roleCode, requested,
                req == null ? null : req.version());
    }

    @ExceptionHandler(RoleAdministration.UnknownRole.class)
    public ResponseEntity<Void> onUnknownRole() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler({RoleAdministration.UnknownPermission.class,
            RoleAdministration.PermissionNotGrantable.class})
    public ResponseEntity<ErrorResponse> onBadPermission(RuntimeException e) {
        String code = e instanceof RoleAdministration.UnknownPermission u ? u.code
                : ((RoleAdministration.PermissionNotGrantable) e).code;
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_permission", code, e.getMessage()));
    }

    /**
     * 409 carrying the state the caller did not have (AC-5). Telling them only that they lost would
     * leave them to reload and guess what changed.
     */
    @ExceptionHandler(RoleAdministration.StaleGrantVersion.class)
    public ResponseEntity<ConflictResponse> onStale(RoleAdministration.StaleGrantVersion e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ConflictResponse(
                "stale_version", e.roleCode, e.currentVersion,
                List.copyOf(e.currentPermissions), e.getMessage()));
    }

    /** 409, not 403: the caller is permitted, the resulting state is what is refused. */
    @ExceptionHandler(RoleAdministration.AdministrativeLockout.class)
    public ResponseEntity<ErrorResponse> onLockout(RoleAdministration.AdministrativeLockout e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("administrative_lockout", "permissions", e.getMessage()));
    }

    /** @param version the token from the read this change is based on */
    public record GrantsRequest(String version, List<String> permissions) {}

    public record ErrorResponse(String error, String field, String message) {}

    public record ConflictResponse(String error, String roleCode, String currentVersion,
                                   List<String> currentPermissions, String message) {}
}
