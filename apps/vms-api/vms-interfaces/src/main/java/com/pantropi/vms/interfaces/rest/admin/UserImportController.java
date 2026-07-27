package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.identity.usecase.UserImport;
import com.pantropi.vms.interfaces.rest.auth.AuthController.AuthenticatedPrincipal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Bulk user import API (US-02.2.2) — guarded by {@code user.manage} via
 * {@link AdminAuthorizationInterceptor} on {@code /api/v1/admin/**}.
 *
 * <ul>
 *   <li>{@code POST /api/v1/admin/users/import/preview} — dry run, no writes (AC-1).</li>
 *   <li>{@code POST /api/v1/admin/users/import} — confirmed, transactional execution (AC-2/AC-3).</li>
 * </ul>
 *
 * <p>The CSV is the raw request body ({@code text/csv}). Delivery of the returned activation tokens
 * is out of band; the email channel arrives in Phase 4.
 */
@RestController
@RequestMapping("/api/v1/admin/users/import")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class UserImportController {

    private final UserImport userImport;

    public UserImportController(UserImport userImport) {
        this.userImport = userImport;
    }

    @PostMapping(value = "/preview", consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public UserImport.Preview preview(@RequestBody String csv) {
        return userImport.preview(csv);
    }

    @PostMapping(consumes = {MediaType.TEXT_PLAIN_VALUE, "text/csv"})
    public ResponseEntity<UserImport.Result> execute(
            @RequestAttribute("vms.principal") AuthenticatedPrincipal actor,
            @RequestBody String csv,
            @RequestParam(defaultValue = "unnamed.csv") String fileName,
            @RequestParam(defaultValue = "false") boolean confirmSkipConflicts) {
        UserImport.Result result = userImport.execute(
                UUID.fromString(actor.userId()), csv, fileName, confirmSkipConflicts);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    // ---- validation failures ----
    @ExceptionHandler(UserImport.PasswordColumnRejected.class)
    public ResponseEntity<Error> onPasswordColumn(UserImport.PasswordColumnRejected e) {
        return ResponseEntity.unprocessableEntity().body(new Error("password_column_rejected", e.getMessage()));
    }

    @ExceptionHandler(UserImport.MalformedFile.class)
    public ResponseEntity<Error> onMalformed(UserImport.MalformedFile e) {
        return ResponseEntity.badRequest().body(new Error("malformed_file", e.getMessage()));
    }

    @ExceptionHandler(UserImport.ConfirmationRequired.class)
    public ResponseEntity<UserImport.Preview> onConfirmation(UserImport.ConfirmationRequired e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.preview);
    }

    public record Error(String error, String message) {}
}
