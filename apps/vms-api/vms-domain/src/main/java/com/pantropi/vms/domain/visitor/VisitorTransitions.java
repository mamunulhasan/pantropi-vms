package com.pantropi.vms.domain.visitor;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Every legal move of a visitor's own status, and nothing else (US-12.2.1, T-12.2.1.1) —
 * {@code TDD-DERIVED} FR-ENT-10 (TDD §4.4).
 *
 * <p>The sibling of {@link RequestTransitions}, one level down. A request's status is decided by
 * people; a visitor's is driven by what physically happened to that person — they arrived, they
 * passed a barrier, they left. Keeping the two tables apart is what stops an approval quietly
 * marking somebody as present.
 *
 * <pre>
 *   PENDING ──► APPROVED ──► CHECKED_IN ──► INSIDE ──► CHECKED_OUT
 *      │            │            │            │
 *      └────────────┴────────────┴────────────┴──► CANCELLED
 *   PENDING/APPROVED ──► EXPIRED | NO_SHOW        (schedule-driven, not an action)
 * </pre>
 *
 * <h2>Why {@code CHECKED_IN → CHECKED_OUT} is legal without passing through {@code INSIDE}</h2>
 * {@code INSIDE} is set by an ACS entry event (US-12.3.1), and this deployment has no barrier
 * feeding it. A visitor checked in at the desk who then leaves must still be checkoutable, or the
 * on-site roll would only ever be correct in a building whose ACS is wired up. The path exists for
 * when it is.
 *
 * <h2>Terminal states are terminal</h2>
 * {@code CHECKED_OUT}, {@code CANCELLED}, {@code EXPIRED} and {@code NO_SHOW} record something that
 * has already happened. Nothing transitions out of them — which is the same rule
 * {@link VisitorCascade} applies from the request side, stated once more here because this is the
 * table an entry-side caller reads.
 *
 * <p>Pure Java: no framework, no I/O.
 */
public final class VisitorTransitions {

    private static final Map<VisitorStatus, Set<VisitorStatus>> ALLOWED = allowed();

    private VisitorTransitions() {
    }

    private static Map<VisitorStatus, Set<VisitorStatus>> allowed() {
        Map<VisitorStatus, Set<VisitorStatus>> map = new EnumMap<>(VisitorStatus.class);
        // Before arrival: the decision path, or the schedule overtaking it.
        map.put(VisitorStatus.PENDING, EnumSet.of(
                VisitorStatus.APPROVED, VisitorStatus.CANCELLED, VisitorStatus.EXPIRED));
        map.put(VisitorStatus.APPROVED, EnumSet.of(
                VisitorStatus.CHECKED_IN, VisitorStatus.CANCELLED, VisitorStatus.EXPIRED,
                VisitorStatus.NO_SHOW));
        // At the desk and past it.
        map.put(VisitorStatus.CHECKED_IN, EnumSet.of(
                VisitorStatus.INSIDE, VisitorStatus.CHECKED_OUT, VisitorStatus.CANCELLED));
        map.put(VisitorStatus.INSIDE, EnumSet.of(VisitorStatus.CHECKED_OUT));
        // Terminal. Listed explicitly with empty sets rather than omitted, so "no transitions" is
        // a stated fact rather than a gap someone later reads as an oversight.
        map.put(VisitorStatus.CHECKED_OUT, EnumSet.noneOf(VisitorStatus.class));
        map.put(VisitorStatus.CANCELLED, EnumSet.noneOf(VisitorStatus.class));
        map.put(VisitorStatus.EXPIRED, EnumSet.noneOf(VisitorStatus.class));
        map.put(VisitorStatus.NO_SHOW, EnumSet.noneOf(VisitorStatus.class));
        return map;
    }

    public static boolean isLegal(VisitorStatus from, VisitorStatus to) {
        return from != null && to != null && ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    /** @throws IllegalVisitorTransition naming both ends, so a refusal can be reported as one */
    public static void require(VisitorStatus from, VisitorStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalVisitorTransition(from, to);
        }
    }

    /** True when nothing moves out of this status — the roll has settled for that person. */
    public static boolean isTerminal(VisitorStatus status) {
        return ALLOWED.getOrDefault(status, Set.of()).isEmpty();
    }

    public static final class IllegalVisitorTransition extends IllegalStateException {
        public final VisitorStatus from;
        public final VisitorStatus to;

        public IllegalVisitorTransition(VisitorStatus from, VisitorStatus to) {
            super("A visitor who is " + from.dbValue() + " cannot become " + to.dbValue());
            this.from = from;
            this.to = to;
        }
    }
}
