package com.pantropi.vms.domain.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.TreeSet;

/**
 * A version token for a role's grant set (US-03.3.1, T-03.3.1.2, AC-5).
 *
 * <p>Derived from the grants themselves — a digest of the sorted permission codes — rather than a
 * counter column. That needs no schema change, but more importantly it is the more correct answer to
 * the question the token is asked: <em>is the set still what the caller was looking at?</em>
 *
 * <p>A counter says no whenever anything was written, including a write that put the set back the
 * way it was. Content says no only when the set genuinely differs, so an administrator is never made
 * to reload a screen that is already accurate. The usual worry about content-addressed versions —
 * the A-B-A sequence, where the value returns to a previous state — is not a hazard here for the
 * same reason: the state the caller expected is the state that exists, so applying their change to
 * it is right.
 *
 * <p>Truncated to twelve characters. This detects concurrent edits by two administrators, not
 * forgery — the endpoint is already behind {@code user.manage}, and someone who holds that can
 * change the grants directly.
 *
 * <p>Pure Java: no framework.
 */
public final class GrantVersion {

    private static final int LENGTH = 12;

    private GrantVersion() {
    }

    /** The token for a set of permission codes. Order-independent; the empty set has one too. */
    public static String of(Set<String> permissionCodes) {
        String canonical = String.join(",", new TreeSet<>(
                permissionCodes == null ? Set.<String>of() : permissionCodes));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
                    .substring(0, LENGTH);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** True when the caller was looking at the set that is there now. */
    public static boolean matches(String presented, Set<String> currentCodes) {
        return presented != null && presented.equals(of(currentCodes));
    }
}
