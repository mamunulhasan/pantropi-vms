package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.SessionStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Session lifecycle: open on login, rotate on refresh, revoke on logout (US-02.1.2).
 *
 * <p>Pure orchestration over ports (no framework). The refresh token is an opaque
 * {@code <sessionId>.<random>} string; only its SHA-256 hash is ever stored (AC-2/AC-5).
 * Rotation is single-use (AC-1); presenting a stale refresh token on a live session is treated
 * as replay, revoking the whole session family and writing a security audit event (AC-5).
 */
public final class SessionManager {

    private final SessionStore sessions;
    private final AccessTokenIssuer tokens;
    private final AuditTrail audit;
    private final Clock clock;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public SessionManager(SessionStore sessions, AccessTokenIssuer tokens, AuditTrail audit,
                          Clock clock, Duration refreshTtl) {
        this.sessions = sessions;
        this.tokens = tokens;
        this.audit = audit;
        this.clock = clock;
        this.refreshTtl = refreshTtl;
    }

    /** Open a fresh session and return the access + refresh pair. */
    public Tokens openSession(UUID userId, String username, String roleCode) {
        return openSession(userId, username, roleCode, false);
    }

    /**
     * Open a session, stamping whether the account must change its password before it can reach
     * anything else (US-02.3.1). The flag lives on the session rather than in the access token so
     * that completing the change takes effect immediately, instead of at token expiry.
     */
    public Tokens openSession(UUID userId, String username, String roleCode,
                              boolean mustChangePassword) {
        UUID sessionId = UUID.randomUUID();
        String refreshToken = sessionId + "." + randomSecret();
        Instant expiry = clock.instant().plus(refreshTtl);
        sessions.create(sessionId, userId, username, roleCode, sha256(refreshToken), expiry,
                mustChangePassword);
        AccessTokenIssuer.IssuedToken access = tokens.issue(userId, username, roleCode, sessionId);
        return new Tokens(access.token(), access.expiresAt(), refreshToken, mustChangePassword);
    }

    /**
     * Exchange a refresh token for a new pair (single-use rotation).
     *
     * @throws InvalidRefreshToken if the token is malformed, unknown, expired or already rotated
     */
    public Tokens refresh(String presentedRefreshToken) {
        UUID sessionId = parseSessionId(presentedRefreshToken);
        Optional<SessionStore.ActiveSession> active = sessionId == null
                ? Optional.empty() : sessions.findActive(sessionId);
        if (active.isEmpty()) {
            throw new InvalidRefreshToken();
        }
        SessionStore.ActiveSession s = active.get();
        String newRefresh = sessionId + "." + randomSecret();
        Instant newExpiry = clock.instant().plus(refreshTtl);

        SessionStore.RotationOutcome outcome = sessions.rotate(
                sessionId, sha256(presentedRefreshToken), sha256(newRefresh), newExpiry);

        switch (outcome) {
            case ROTATED -> {
                AccessTokenIssuer.IssuedToken access =
                        tokens.issue(s.userId(), s.username(), s.roleCode(), sessionId);
                // Refreshing does not shed the obligation: the flag lives on the session row and
                // is carried forward, so a client cannot escape the gate by rotating its token.
                return new Tokens(access.token(), access.expiresAt(), newRefresh,
                        s.mustChangePassword());
            }
            case REPLAY_DETECTED -> {
                sessions.revoke(sessionId, "refresh_token_replay");
                audit.record(s.userId(), "session.replay_detected", "session",
                        sessionId.toString(), "family revoked on stale refresh token");
                throw new InvalidRefreshToken();
            }
            default -> throw new InvalidRefreshToken();
        }
    }

    /** Revoke a session (logout). Idempotent and safe on an unknown session. */
    public void logout(UUID sessionId, UUID userId) {
        sessions.revoke(sessionId, "logout");
        audit.record(userId, "session.logout", "session", sessionId.toString(), null);
    }

    private String randomSecret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static UUID parseSessionId(String refreshToken) {
        if (refreshToken == null) return null;
        int dot = refreshToken.indexOf('.');
        if (dot <= 0) return null;
        try {
            return UUID.fromString(refreshToken.substring(0, dot));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Tokens(String accessToken, Instant accessExpiresAt, String refreshToken,
                         boolean mustChangePassword) {}

    public static final class InvalidRefreshToken extends RuntimeException {
        public InvalidRefreshToken() {
            super("Invalid or expired refresh token");
        }
    }
}
