package com.pantropi.vms.domain.identity;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What one principal is allowed to do (US-03.1.1, T-03.1.1.1, AC-2).
 *
 * <p>A type rather than a bare {@code Set<String>} for one reason: {@link #NONE} exists and is a
 * legitimate answer. An inactive user, or one whose role has no grants, resolves to the empty set —
 * and every call they make is then denied (AC-5). Modelling that as an empty collection is right;
 * modelling it as null would make "we could not resolve this" and "this principal may do nothing"
 * the same value, and the natural handling of null is the wrong one.
 *
 * <p>Immutable, so a resolved set cannot be widened after the decision that used it.
 *
 * <p>Pure Java: no framework.
 */
public final class PermissionSet {

    /** No permissions at all. Deny everything — the correct resolution, not a failure. */
    public static final PermissionSet NONE = new PermissionSet(Set.of());

    private final Set<String> codes;

    private PermissionSet(Set<String> codes) {
        this.codes = codes;
    }

    public static PermissionSet of(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return NONE;
        }
        return new PermissionSet(Collections.unmodifiableSet(new LinkedHashSet<>(codes)));
    }

    public boolean allows(String permissionCode) {
        return permissionCode != null && codes.contains(permissionCode);
    }

    public Set<String> codes() {
        return codes;
    }

    public boolean isEmpty() {
        return codes.isEmpty();
    }

    @Override
    public String toString() {
        return "PermissionSet" + codes;
    }
}
