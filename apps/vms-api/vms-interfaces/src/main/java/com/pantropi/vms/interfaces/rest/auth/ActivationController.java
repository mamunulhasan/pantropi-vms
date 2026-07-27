package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.usecase.AccountActivation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Account activation endpoint (US-02.2.2, T-02.2.2.3) — FR-ADM-02 (SRS B1).
 *
 * <p>{@code POST /api/v1/auth/activate} is public: it is reached with a single-use activation
 * token, which the user redeems to set their own initial password. No administrator ever transmits
 * a password. Left open in {@link AuthWebConfig} alongside login and refresh.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class ActivationController {

    private final AccountActivation activation;

    public ActivationController(AccountActivation activation) {
        this.activation = activation;
    }

    @PostMapping("/activate")
    public ResponseEntity<Void> activate(@RequestBody ActivateRequest req) {
        if (req == null || req.token() == null || req.password() == null) {
            return ResponseEntity.badRequest().build();
        }
        activation.activate(req.token(), req.password().toCharArray());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AccountActivation.InvalidToken.class)
    public ResponseEntity<Error> onInvalidToken() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new Error("invalid_activation_token", "Invalid or expired activation token"));
    }

    @ExceptionHandler(AccountActivation.WeakPassword.class)
    public ResponseEntity<Error> onWeakPassword(AccountActivation.WeakPassword e) {
        return ResponseEntity.badRequest().body(new Error("weak_password", e.getMessage()));
    }

    public record ActivateRequest(String token, String password) {}
    public record Error(String error, String message) {}
}
