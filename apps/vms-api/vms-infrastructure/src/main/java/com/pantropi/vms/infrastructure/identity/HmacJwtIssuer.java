package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal HS256 JWT issuer/verifier (US-02.1.1/US-02.1.2) built on the JDK's HMAC — no JWT library.
 *
 * <p>Deliberately fixes the algorithm to HS256 and never reads {@code alg} from an incoming
 * token: verification recomputes the HS256 signature with the server secret and compares it in
 * constant time. This closes the "alg" confusion / {@code alg:none} class of JWT attacks by
 * construction. Claims carried: {@code sub}, {@code preferred_username}, {@code role},
 * {@code sid} (session id), {@code iat}, {@code exp}.
 *
 * <p>Replace with a vetted JWT library behind this same port once dependencies are available. The
 * secret comes from configuration/environment, never source (FR-API-03).
 */
public final class HmacJwtIssuer implements AccessTokenIssuer {

    private static final String HEADER_B64 =
            base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final byte[] secret;
    private final Duration ttl;
    private final java.time.Clock clock;

    public HmacJwtIssuer(String secret, Duration ttl, java.time.Clock clock) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) {
            throw new IllegalArgumentException(
                    "JWT secret must be at least 32 bytes; configure vms.security.jwt.secret");
        }
        this.secret = key;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public IssuedToken issue(UUID userId, String username, String roleCode, UUID sessionId) {
        Instant now = clock.instant();
        Instant exp = now.plus(ttl);
        String payload = "{"
                + "\"sub\":\"" + userId + "\","
                + "\"preferred_username\":\"" + esc(username) + "\","
                + "\"role\":\"" + esc(roleCode) + "\","
                + "\"sid\":\"" + sessionId + "\","
                + "\"iat\":" + now.getEpochSecond() + ","
                + "\"exp\":" + exp.getEpochSecond()
                + "}";
        String signingInput = HEADER_B64 + "." + base64Url(payload.getBytes(StandardCharsets.UTF_8));
        String sig = base64Url(hmac(signingInput.getBytes(StandardCharsets.US_ASCII)));
        return new IssuedToken(signingInput + "." + sig, exp);
    }

    @Override
    public Optional<VerifiedToken> verify(String token) {
        if (token == null) return Optional.empty();
        String[] p = token.split("\\.");
        if (p.length != 3 || !p[0].equals(HEADER_B64)) return Optional.empty();

        String signingInput = p[0] + "." + p[1];
        byte[] expectedSig = hmac(signingInput.getBytes(StandardCharsets.US_ASCII));
        byte[] presentedSig;
        try {
            presentedSig = Base64.getUrlDecoder().decode(p[2]);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(expectedSig, presentedSig)) return Optional.empty();

        String payload = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8);
        long exp = longClaim(payload, "exp");
        if (Instant.ofEpochSecond(exp).isBefore(clock.instant())) return Optional.empty();

        return Optional.of(new VerifiedToken(
                UUID.fromString(strClaim(payload, "sub")),
                strClaim(payload, "preferred_username"),
                strClaim(payload, "role"),
                UUID.fromString(strClaim(payload, "sid")),
                Instant.ofEpochSecond(exp)));
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    private static String base64Url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String strClaim(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"");
        if (i < 0) return null;
        int start = i + key.length() + 4;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static long longClaim(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        int start = i + key.length() + 3;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        return Long.parseLong(json.substring(start, end));
    }
}
