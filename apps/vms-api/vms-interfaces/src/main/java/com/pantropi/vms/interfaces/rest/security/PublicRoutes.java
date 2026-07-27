package com.pantropi.vms.interfaces.rest.security;

import java.util.List;
import java.util.Set;

/**
 * THE reviewed public-path allowlist (US-03.2.1, T-03.2.1.1, AC-1/AC-3).
 *
 * <p>A single declaration, deliberately short, so the entire unauthenticated surface of the API can
 * be reviewed in one place. Everything not listed here requires authentication; every authenticated
 * route must additionally declare {@link RequiresPermission} or {@link RequiresAuthentication}.
 *
 * <p>Adding an entry here widens the unauthenticated attack surface and is a security decision —
 * it belongs in a pull request with a reason, never as a convenience.
 */
public final class PublicRoutes {

    private PublicRoutes() {
    }

    /**
     * Exact paths reachable without a token, each with the reason it must be public.
     */
    public static final Set<String> EXACT = Set.of(
            "/api/v1/auth/login",     // mints the first token; cannot itself require one
            "/api/v1/auth/refresh",   // exchanges a refresh token; access token may be expired
            "/api/v1/auth/activate",  // redeemed with a single-use activation token (US-02.2.2)
            "/error"                  // container error dispatch; never a business route
    );

    /**
     * Prefixes reachable without a token. Actuator exposure is itself restricted to health by
     * configuration ({@code management.endpoints.web.exposure.include}); in a hardened deployment
     * it moves to a separate management port (TDD §9.3).
     */
    public static final List<String> PREFIXES = List.of(
            "/actuator/health"
    );

    /** True when the request path needs no authentication. */
    public static boolean isPublic(String path) {
        if (path == null) {
            return false;
        }
        if (EXACT.contains(path)) {
            return true;
        }
        for (String prefix : PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
