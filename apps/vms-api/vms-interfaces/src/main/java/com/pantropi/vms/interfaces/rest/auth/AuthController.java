package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.usecase.AuthenticateUser;
import com.pantropi.vms.application.identity.usecase.SessionManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Authentication endpoints (US-02.1.1 / US-02.1.2) — FR-ADM-02 (SRS B1).
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — verify credentials, open a session, return an access +
 *       refresh pair.</li>
 *   <li>{@code POST /api/v1/auth/refresh} — single-use refresh rotation (AC-1).</li>
 *   <li>{@code POST /api/v1/auth/logout} — revoke the current session (AC-2). Protected.</li>
 *   <li>{@code GET  /api/v1/auth/me} — echo the authenticated principal. Protected.</li>
 * </ul>
 *
 * <p>The controller holds no logic beyond HTTP mapping — it delegates to the use cases.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class AuthController {

    private final AuthenticateUser authenticateUser;
    private final SessionManager sessions;

    public AuthController(AuthenticateUser authenticateUser, SessionManager sessions) {
        this.authenticateUser = authenticateUser;
        this.sessions = sessions;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            return ResponseEntity.badRequest().build();
        }
        AuthenticateUser.AuthenticatedUser user =
                authenticateUser.login(req.username(), req.password().toCharArray());
        SessionManager.Tokens t = sessions.openSession(user.id(), user.username(), user.roleCode());
        return ResponseEntity.ok(toResponse(t));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest req) {
        if (req == null || req.refreshToken() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(toResponse(sessions.refresh(req.refreshToken())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestAttribute("vms.principal") AuthenticatedPrincipal principal) {
        sessions.logout(UUID.fromString(principal.sessionId()), UUID.fromString(principal.userId()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public MeResponse me(@RequestAttribute("vms.principal") AuthenticatedPrincipal principal) {
        return new MeResponse(principal.userId(), principal.username(), principal.role());
    }

    @ExceptionHandler(AuthenticateUser.InvalidCredentials.class)
    public ResponseEntity<ErrorResponse> onInvalidCredentials() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("invalid_credentials", "Invalid username or password"));
    }

    @ExceptionHandler(SessionManager.InvalidRefreshToken.class)
    public ResponseEntity<ErrorResponse> onInvalidRefresh() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("invalid_refresh_token", "Invalid or expired refresh token"));
    }

    private static TokenResponse toResponse(SessionManager.Tokens t) {
        return new TokenResponse(t.accessToken(), "Bearer", t.accessExpiresAt(), t.refreshToken());
    }

    public record LoginRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt,
                                String refreshToken) {}
    public record MeResponse(String userId, String username, String role) {}
    public record ErrorResponse(String error, String message) {}
    public record AuthenticatedPrincipal(String userId, String username, String role,
                                         String sessionId) {}
}
