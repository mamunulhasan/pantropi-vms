package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.masterdata.SettingsCatalogue;
import com.pantropi.vms.application.masterdata.usecase.SystemSettings;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * System settings API (US-04.8.1) — FR-SET-01.
 *
 * <h2>Reading and writing are deliberately different permissions</h2>
 * The class requires {@code masterdata.view}, so anyone who can see reference data can see how the
 * system is configured. Writing overrides that with {@code settings.manage} at the method level.
 *
 * <p>That split is the point of the story, not an accident of convenience: changing
 * {@code acs.retry.max_attempts} or a notification switch alters how the whole installation
 * behaves, which is a different kind of act from editing a floor's name. Someone who administers
 * master data does not thereby get to change application behaviour (AC-4).
 *
 * <p>The controller holds no logic beyond HTTP mapping.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class SettingsController {

    private final SystemSettings settings;

    public SettingsController(SystemSettings settings) {
        this.settings = settings;
    }

    @GetMapping
    public List<SystemSettings.Setting> list() {
        return settings.list();
    }

    @GetMapping("/{key}")
    public SystemSettings.Setting get(@PathVariable String key) {
        return settings.get(key);
    }

    /** Method-level declaration wins over the class default — writes need {@code settings.manage}. */
    @RequiresPermission("settings.manage")
    @PutMapping("/{key}")
    public SystemSettings.Setting update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable String key, @RequestBody UpdateRequest req) {
        return settings.update(UUID.fromString(actor.userId()), key,
                req == null ? null : req.value());
    }

    @ExceptionHandler(SettingsCatalogue.UnknownSetting.class)
    public ResponseEntity<ErrorResponse> onUnknown(SettingsCatalogue.UnknownSetting e) {
        // 404 on a GET of an uncatalogued key, 404 on a write too: the key does not exist as far as
        // this application is concerned, and inventing one is exactly what AC-5 forbids.
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("unknown_setting", e.key, e.getMessage()));
    }

    @ExceptionHandler(SettingsCatalogue.InvalidSettingValue.class)
    public ResponseEntity<ErrorResponse> onInvalidValue(SettingsCatalogue.InvalidSettingValue e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid_value", e.key, e.getMessage()));
    }

    /**
     * 409, not 400: the request is well formed and the value is valid for the key. What forbids it
     * is the current state of an open question, which may change — so this is a conflict with
     * present policy rather than a malformed request.
     */
    @ExceptionHandler(SystemSettings.ChangeRefused.class)
    public ResponseEntity<ErrorResponse> onRefused(SystemSettings.ChangeRefused e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("change_refused", e.key, e.getMessage()));
    }

    public record UpdateRequest(String value) {}
    public record ErrorResponse(String error, String key, String message) {}
}
