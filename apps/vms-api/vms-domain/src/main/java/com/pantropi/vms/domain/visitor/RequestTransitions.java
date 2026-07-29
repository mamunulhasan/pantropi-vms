package com.pantropi.vms.domain.visitor;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The legal state machine for a visitor request (US-07.5.1, T-07.5.1.1) — TDD §4.2, and the
 * integrity control behind FR-VMS-02 (SRS B1).
 *
 * <p>This is what stops a rejection becoming an approval. Every status change goes through
 * {@link #require}; the aggregate has no other way to move, so no interface-layer path and no
 * repository can route around it (AC-4).
 *
 * <h2>Declared as a table, not as scattered conditions</h2>
 * The transitions are data, so the whole machine can be read in one place and asserted cell by cell.
 * Written as {@code if} statements across three methods, the answer to "can an approved request be
 * rejected?" would be somewhere in the middle of a method about rejection.
 *
 * <pre>
 *   SUBMITTED → APPROVED, REJECTED, CANCELLED
 *   APPROVED  → CANCELLED          (an approval can be withdrawn; it cannot become a rejection)
 *   REJECTED  → (terminal)
 *   CANCELLED → (terminal)
 * </pre>
 *
 * <p>{@code APPROVED → REJECTED} is deliberately absent. Reversing an approval is a cancellation and
 * the two are not interchangeable — a rejection says the request was never granted, and a request
 * that was approved and then withdrawn is a different fact about the world.
 *
 * <p>Pure Java: no framework.
 */
public final class RequestTransitions {

    private static final Map<RequestStatus, Set<RequestStatus>> LEGAL = legalTransitions();

    private RequestTransitions() {
    }

    private static Map<RequestStatus, Set<RequestStatus>> legalTransitions() {
        Map<RequestStatus, Set<RequestStatus>> table = new EnumMap<>(RequestStatus.class);
        table.put(RequestStatus.SUBMITTED, EnumSet.of(
                RequestStatus.APPROVED, RequestStatus.REJECTED, RequestStatus.CANCELLED));
        table.put(RequestStatus.APPROVED, EnumSet.of(RequestStatus.CANCELLED));
        table.put(RequestStatus.REJECTED, EnumSet.noneOf(RequestStatus.class));
        table.put(RequestStatus.CANCELLED, EnumSet.noneOf(RequestStatus.class));
        return table;
    }

    public static boolean isLegal(RequestStatus from, RequestStatus to) {
        return from != null && to != null && LEGAL.getOrDefault(from, Set.of()).contains(to);
    }

    /** Where a request in this state may go. Empty for a terminal state. */
    public static Set<RequestStatus> legalTargets(RequestStatus from) {
        return Set.copyOf(LEGAL.getOrDefault(from, Set.of()));
    }

    /**
     * @throws IllegalTransition if the move is not in the table — the single point at which a
     *                           status change is permitted or refused (AC-4)
     */
    public static void require(RequestStatus from, RequestStatus to) {
        if (!isLegal(from, to)) {
            throw new IllegalTransition(from, to);
        }
    }

    /**
     * Every status the table has an entry for.
     *
     * <p>Exposed so the exhaustiveness test can compare it against the enum: adding a value to
     * {@link RequestStatus} without extending this table must fail the build rather than produce a
     * state with no rules, which {@link #isLegal} would treat as permitting nothing and which would
     * therefore be silently unusable (AC-6).
     */
    public static Set<RequestStatus> mappedStates() {
        return Set.copyOf(LEGAL.keySet());
    }

    /** Names both ends, so a failure says what was attempted rather than only that it failed. */
    public static final class IllegalTransition extends RuntimeException {
        public final RequestStatus from;
        public final RequestStatus to;

        public IllegalTransition(RequestStatus from, RequestStatus to) {
            super("A request cannot move from " + from + " to " + to
                    + "; legal moves from " + from + " are " + legalTargets(from));
            this.from = from;
            this.to = to;
        }
    }
}
