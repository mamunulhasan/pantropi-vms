package com.pantropi.vms.interfaces.rest.security;

/**
 * The acting principal, derived <strong>solely</strong> from a verified access token whose session
 * is live (US-03.2.1, AC-4).
 *
 * <p>Nothing in a request body, query string or header other than the validated {@code Authorization}
 * token can influence these values, so a client cannot assert a different acting user.
 */
public record AuthenticatedPrincipal(String userId, String username, String role, String sessionId) {

    /** Request attribute under which the interceptor publishes the principal for handlers. */
    public static final String ATTRIBUTE = "vms.principal";
}
