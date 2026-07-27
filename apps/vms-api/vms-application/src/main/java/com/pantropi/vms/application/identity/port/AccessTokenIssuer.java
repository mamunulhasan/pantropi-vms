package com.pantropi.vms.application.identity.port;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound port: issue and verify short-lived access tokens (US-02.1.1, AC — JWT session).
 *
 * <p>The current adapter mints HS256 JWTs with the JDK's HMAC. When Spring Security / a JWT
 * library becomes available it replaces this adapter behind the same port.
 */
public interface AccessTokenIssuer {

    IssuedToken issue(UUID userId, String username, String roleCode);

    /** Verify a token's signature and expiry; empty when invalid or expired. */
    java.util.Optional<VerifiedToken> verify(String token);

    record IssuedToken(String token, Instant expiresAt) {}

    record VerifiedToken(UUID userId, String username, String roleCode, Instant expiresAt) {}
}
