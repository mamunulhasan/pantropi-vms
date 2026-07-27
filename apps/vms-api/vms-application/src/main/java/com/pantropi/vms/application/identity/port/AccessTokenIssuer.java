package com.pantropi.vms.application.identity.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: issue and verify short-lived access tokens (US-02.1.1 / US-02.1.2).
 *
 * <p>The token carries a session id ({@code sid}) so the validation path can consult the
 * {@link SessionStore} and reject a revoked session before the token would expire (US-02.1.2).
 * The current adapter mints HS256 JWTs with the JDK's HMAC.
 */
public interface AccessTokenIssuer {

    IssuedToken issue(UUID userId, String username, String roleCode, UUID sessionId);

    /** Verify a token's signature and expiry; empty when invalid or expired. */
    Optional<VerifiedToken> verify(String token);

    record IssuedToken(String token, Instant expiresAt) {}

    record VerifiedToken(UUID userId, String username, String roleCode, UUID sessionId,
                         Instant expiresAt) {}
}
