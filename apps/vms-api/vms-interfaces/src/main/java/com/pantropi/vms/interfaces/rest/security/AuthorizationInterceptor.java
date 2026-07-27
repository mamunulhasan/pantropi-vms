package com.pantropi.vms.interfaces.rest.security;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.PermissionChecker;
import com.pantropi.vms.application.identity.port.SessionStore;
import com.pantropi.vms.interfaces.rest.error.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

/**
 * The single authorization gate for the whole API (US-03.2.1) — NFR-SEC-01 (SRS B1), OWASP ASVS V4.
 *
 * <p>Registered on {@code /**}, so it sees every mapped route. A request is allowed only when one of
 * the following holds:
 * <ol>
 *   <li>the path is in the reviewed {@link PublicRoutes} allowlist; or</li>
 *   <li>it carries a verifiable access token whose session is still live, <em>and</em> the handler
 *       declares {@link RequiresAuthentication}; or</li>
 *   <li>the same, and the handler declares {@link RequiresPermission} which the principal's role
 *       holds.</li>
 * </ol>
 *
 * <p><strong>Everything else is denied.</strong> A handler that declares nothing is refused even for
 * a valid administrator token, so a newly added endpoint cannot accidentally be public (AC-1, AC-3).
 *
 * <p>Ordering matters for diagnosis: authentication failures — including a revoked or expired
 * session, which the {@link SessionStore} check catches — produce <strong>401</strong>, while an
 * authenticated principal lacking the permission produces <strong>403</strong> (AC-6). The decision
 * is made in {@code preHandle}, before the handler and before any read of the target resource, so a
 * denial performs no work and reveals nothing about whether that resource exists (AC-2, AC-5).
 *
 * <p>This realises the "security filter chain" of T-03.2.1.1 with the interceptor mechanism, because
 * Spring Security is not available in this build — the same documented deviation as US-02.1.1. The
 * decision points are isolated here, so adopting Spring Security later replaces this class alone.
 */
public final class AuthorizationInterceptor implements HandlerInterceptor {

    private final AccessTokenIssuer tokens;
    private final SessionStore sessions;
    private final PermissionChecker permissions;
    private final AuthorizationDenialRecorder denials;

    public AuthorizationInterceptor(AccessTokenIssuer tokens, SessionStore sessions,
                                    PermissionChecker permissions,
                                    AuthorizationDenialRecorder denials) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.permissions = permissions;
        this.denials = denials;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI();

        // CORS preflight carries no credentials by design; the CORS configuration decides it.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (PublicRoutes.isPublic(path)) {
            return true;
        }

        // ---- authenticate (401 on any failure) ----
        Optional<AccessTokenIssuer.VerifiedToken> verified = bearerToken(request)
                .flatMap(tokens::verify);
        if (verified.isEmpty()) {
            return deny(request, response, HttpServletResponse.SC_UNAUTHORIZED, null, null);
        }
        AccessTokenIssuer.VerifiedToken token = verified.get();

        // AC-6: a signature-valid token whose session was revoked or has expired is 401, not 403.
        if (sessions.findActive(token.sessionId()).isEmpty()) {
            return deny(request, response, HttpServletResponse.SC_UNAUTHORIZED, null, token.userId());
        }

        // ---- authorize (403 when authenticated but not permitted) ----
        Declaration declaration = declarationOf(handler);
        if (declaration.kind() == Kind.NONE) {
            // Deny-by-default: the route declared nothing and is not allowlisted.
            return deny(request, response, HttpServletResponse.SC_FORBIDDEN,
                    "<undeclared>", token.userId());
        }
        if (declaration.kind() == Kind.PERMISSION
                && !permissions.roleHasPermission(token.roleCode(), declaration.permission())) {
            return deny(request, response, HttpServletResponse.SC_FORBIDDEN,
                    declaration.permission(), token.userId());
        }

        // AC-4: the principal comes only from the validated token — never from client input.
        request.setAttribute(AuthenticatedPrincipal.ATTRIBUTE, new AuthenticatedPrincipal(
                token.userId().toString(), token.username(), token.roleCode(),
                token.sessionId().toString()));
        return true;
    }

    private boolean deny(HttpServletRequest request, HttpServletResponse response, int status,
                         String attemptedPermission, UUID actorId) {
        denials.record(request, status, attemptedPermission, actorId);
        try {
            // Rendered through the same writer the exception advice uses, so a denial body is
            // byte-identical to any other error of that status (US-03.2.2 AC-1).
            ProblemDetails.write(request, response, HttpStatus.valueOf(status),
                    CorrelationIdFilter.of(request));
        } catch (IOException e) {
            response.setStatus(status);   // fail closed even if the body cannot be written
        }
        return false;
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String value = header.substring(7).trim();
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** Resolve the handler's security declaration; method-level wins over class-level. */
    static Declaration declarationOf(Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return new Declaration(Kind.NONE, null);
        }
        RequiresPermission methodPerm = hm.getMethodAnnotation(RequiresPermission.class);
        if (methodPerm != null) {
            return new Declaration(Kind.PERMISSION, methodPerm.value());
        }
        if (hm.getMethodAnnotation(RequiresAuthentication.class) != null) {
            return new Declaration(Kind.AUTHENTICATED, null);
        }
        RequiresPermission typePerm = hm.getBeanType().getAnnotation(RequiresPermission.class);
        if (typePerm != null) {
            return new Declaration(Kind.PERMISSION, typePerm.value());
        }
        if (hm.getBeanType().getAnnotation(RequiresAuthentication.class) != null) {
            return new Declaration(Kind.AUTHENTICATED, null);
        }
        return new Declaration(Kind.NONE, null);
    }

    enum Kind { NONE, AUTHENTICATED, PERMISSION }

    record Declaration(Kind kind, String permission) {}
}
