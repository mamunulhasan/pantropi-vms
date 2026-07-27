package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.usecase.AuthenticateUser;
import com.pantropi.vms.application.identity.usecase.ChangePassword;
import com.pantropi.vms.application.identity.usecase.PasswordPolicy;
import com.pantropi.vms.application.identity.usecase.SessionManager;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresAuthentication;
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
 *   <li>{@code POST /api/v1/auth/password} — change your own password (US-02.3.1). Protected.</li>
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
    private final ChangePassword changePassword;

    public AuthController(AuthenticateUser authenticateUser, SessionManager sessions,
                          ChangePassword changePassword) {
        this.authenticateUser = authenticateUser;
        this.sessions = sessions;
        this.changePassword = changePassword;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@RequestBody LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null) {
            return ResponseEntity.badRequest().build();
        }
        AuthenticateUser.AuthenticatedUser user =
                authenticateUser.login(req.username(), req.password().toCharArray());
        SessionManager.Tokens t = sessions.openSession(user.id(), user.username(), user.roleCode(),
                user.mustChangePassword());
        return ResponseEntity.ok(toResponse(t));
    }

    /**
     * Change your own password (US-02.3.1). Requires the current password, so a stolen access token
     * alone cannot take over the account.
     *
     * <p>Succeeding revokes every session of the user — including this one — and immediately opens
     * a fresh one, so the response carries a new token pair that the client should adopt. Any other
     * device the account was signed in on is signed out.
     */
    @RequiresAuthentication
    @PostMapping("/password")
    public ResponseEntity<TokenResponse> changePassword(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @RequestBody ChangePasswordRequest req) {
        if (req == null || req.currentPassword() == null || req.newPassword() == null) {
            return ResponseEntity.badRequest().build();
        }
        UUID userId = UUID.fromString(principal.userId());
        changePassword.change(userId, req.currentPassword().toCharArray(),
                req.newPassword().toCharArray());
        return ResponseEntity.ok(toResponse(
                sessions.openSession(userId, principal.username(), principal.role(), false)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@RequestBody RefreshRequest req) {
        if (req == null || req.refreshToken() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(toResponse(sessions.refresh(req.refreshToken())));
    }

    @RequiresAuthentication
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal) {
        sessions.logout(UUID.fromString(principal.sessionId()), UUID.fromString(principal.userId()));
        return ResponseEntity.noContent().build();
    }

    @RequiresAuthentication
    @GetMapping("/me")
    public MeResponse me(@RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal) {
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

    /**
     * Policy rejections are 422, not 400: the request was well formed and understood, it just asked
     * for something the policy forbids. The message is the guidance the policy composed — it never
     * names the rule that matched a denylist entry, and never echoes the candidate password.
     */
    @ExceptionHandler(PasswordPolicy.WeakPassword.class)
    public ResponseEntity<ErrorResponse> onWeakPassword(PasswordPolicy.WeakPassword e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("weak_password", e.getMessage()));
    }

    @ExceptionHandler(ChangePassword.PasswordUnchanged.class)
    public ResponseEntity<ErrorResponse> onPasswordUnchanged(ChangePassword.PasswordUnchanged e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("password_unchanged", e.getMessage()));
    }

    private static TokenResponse toResponse(SessionManager.Tokens t) {
        return new TokenResponse(t.accessToken(), "Bearer", t.accessExpiresAt(), t.refreshToken(),
                t.mustChangePassword());
    }

    public record LoginRequest(String username, String password) {}
    public record RefreshRequest(String refreshToken) {}
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    /**
     * @param mustChangePassword when true the session may reach only the password-change endpoints
     *                           until the change is made (US-02.3.1)
     */
    public record TokenResponse(String accessToken, String tokenType, Instant expiresAt,
                                String refreshToken, boolean mustChangePassword) {}
    public record MeResponse(String userId, String username, String role) {}
    public record ErrorResponse(String error, String message) {}
}
