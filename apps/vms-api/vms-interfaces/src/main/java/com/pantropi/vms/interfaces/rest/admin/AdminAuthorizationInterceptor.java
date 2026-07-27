package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.PermissionChecker;
import com.pantropi.vms.application.identity.port.SessionStore;
import com.pantropi.vms.interfaces.rest.auth.AuthController.AuthenticatedPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Authorises the user-administration API (US-02.2.1, AC-6). Deny-by-default: a request must carry a
 * valid Bearer token on a live session AND the principal's role must hold the required permission,
 * or it is rejected — 401 when unauthenticated, 403 when authenticated but not permitted, with an
 * authorization-denial audit event on the 403 path.
 */
public final class AdminAuthorizationInterceptor implements HandlerInterceptor {

    private final AccessTokenIssuer tokens;
    private final SessionStore sessions;
    private final PermissionChecker permissions;
    private final AuditTrail audit;
    private final String requiredPermission;

    public AdminAuthorizationInterceptor(AccessTokenIssuer tokens, SessionStore sessions,
                                         PermissionChecker permissions, AuditTrail audit,
                                         String requiredPermission) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.permissions = permissions;
        this.audit = audit;
        this.requiredPermission = requiredPermission;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        Optional<AccessTokenIssuer.VerifiedToken> verified = tokens.verify(auth.substring(7).trim());
        if (verified.isEmpty() || sessions.findActive(verified.get().sessionId()).isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        AccessTokenIssuer.VerifiedToken v = verified.get();

        if (!permissions.roleHasPermission(v.roleCode(), requiredPermission)) {
            audit.record(v.userId(), "authorization.denied", "permission", requiredPermission,
                    "role=" + v.roleCode());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }

        request.setAttribute("vms.principal", new AuthenticatedPrincipal(
                v.userId().toString(), v.username(), v.roleCode(), v.sessionId().toString()));
        return true;
    }
}
