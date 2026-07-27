package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.usecase.AuthenticateUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * Authentication endpoints (US-02.1.1) — FR-ADM-02 (SRS B1).
 *
 * <p>{@code POST /api/v1/auth/login} issues a session token; {@code GET /api/v1/auth/me}
 * echoes the authenticated principal, proving the token round-trips. The controller holds no
 * logic beyond HTTP mapping — it delegates to the use case (US-01.2.1 boundary).
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class AuthController {

    private final AuthenticateUser authenticateUser;

    public AuthController(AuthenticateUser authenticateUser) {
        this.authenticateUser = authenticateUser;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            return ResponseEntity.badRequest().build();
        }
        AccessTokenIssuer.IssuedToken t =
                authenticateUser.login(req.username(), req.password().toCharArray());
        return ResponseEntity.ok(new LoginResponse(t.token(), "Bearer", t.expiresAt()));
    }

    @GetMapping("/me")
    public MeResponse me(@RequestAttribute("vms.principal") AuthenticatedPrincipal principal) {
        return new MeResponse(principal.userId(), principal.username(), principal.role());
    }

    /** Uniform 401 — never reveals which credential check failed (OWASP). */
    @ExceptionHandler(AuthenticateUser.InvalidCredentials.class)
    public ResponseEntity<ErrorResponse> onInvalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("invalid_credentials", "Invalid username or password"));
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt) {}
    public record MeResponse(String userId, String username, String role) {}
    public record ErrorResponse(String error, String message) {}
    public record AuthenticatedPrincipal(String userId, String username, String role) {}
}
