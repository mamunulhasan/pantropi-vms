package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.SessionStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Validates the {@code Authorization: Bearer <jwt>} header on protected routes (US-02.1.1) AND
 * consults the {@link SessionStore} so a revoked or expired session is rejected before its access
 * token would expire (US-02.1.2, AC-2/AC-3).
 *
 * <p>Deny-by-default: a route wired through this interceptor requires a valid, unexpired token
 * whose session is still active, or the request is rejected 401 before reaching the controller.
 */
public final class BearerTokenInterceptor implements HandlerInterceptor {

    private final AccessTokenIssuer tokens;
    private final SessionStore sessions;

    public BearerTokenInterceptor(AccessTokenIssuer tokens, SessionStore sessions) {
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        Optional<AccessTokenIssuer.VerifiedToken> verified = tokens.verify(auth.substring(7).trim());
        if (verified.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        AccessTokenIssuer.VerifiedToken v = verified.get();

        // Server-side revocation check: the token's signature can be valid while the session is
        // gone (logout, replay revocation, user deactivation). The store is the authority.
        if (sessions.findActive(v.sessionId()).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }

        request.setAttribute("vms.principal",
                new AuthController.AuthenticatedPrincipal(
                        v.userId().toString(), v.username(), v.roleCode(), v.sessionId().toString()));
        return true;
    }
}
