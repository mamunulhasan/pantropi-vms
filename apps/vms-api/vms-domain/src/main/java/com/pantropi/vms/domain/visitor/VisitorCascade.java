package com.pantropi.vms.domain.visitor;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * How a request-level decision reaches the visitors attached to it (US-07.5.1, T-07.5.1.2, AC-3).
 *
 * <pre>
 *   request → APPROVED   ⇒ visitor → APPROVED
 *   request → REJECTED   ⇒ visitor → CANCELLED
 *   request → CANCELLED  ⇒ visitor → CANCELLED
 * </pre>
 *
 * <h2>A visitor already in a terminal state is not moved</h2>
 * AC-3 names the case that matters: a visitor individually cancelled — withdrawn by the tenant
 * before the decision — must not be resurrected when the request is approved. Someone deliberately
 * removed that person from the visit, and a later approval of the request as a whole is not a
 * decision about them.
 *
 * <p>The same reasoning covers the other terminal statuses, so they are listed rather than left to
 * be discovered: a visitor who has already checked out, expired, or been recorded as a no-show is
 * describing something that happened, and a request-level transition cannot un-happen it. Only
 * {@code CANCELLED} arises in this phase; the rest are named now so the rule does not have to be
 * rediscovered when they do.
 *
 * <p>Pure Java: no framework.
 */
public final class VisitorCascade {

    /**
     * Statuses that record something already settled. A cascade never moves a visitor out of one.
     */
    private static final Set<VisitorStatus> TERMINAL = EnumSet.of(
            VisitorStatus.CANCELLED,
            VisitorStatus.CHECKED_OUT,
            VisitorStatus.EXPIRED,
            VisitorStatus.NO_SHOW);

    private static final Map<RequestStatus, VisitorStatus> CASCADE = cascade();

    private VisitorCascade() {
    }

    private static Map<RequestStatus, VisitorStatus> cascade() {
        Map<RequestStatus, VisitorStatus> map = new EnumMap<>(RequestStatus.class);
        map.put(RequestStatus.APPROVED, VisitorStatus.APPROVED);
        map.put(RequestStatus.REJECTED, VisitorStatus.CANCELLED);
        map.put(RequestStatus.CANCELLED, VisitorStatus.CANCELLED);
        // SUBMITTED is deliberately absent: it is the state a request is created in, never
        // transitioned to, so there is no cascade for it.
        return map;
    }

    /**
     * The status a visitor takes when the request moves here, or empty when nothing should change.
     *
     * @param current the visitor's status now — a terminal one yields empty
     */
    public static Optional<VisitorStatus> targetFor(RequestStatus requestStatus,
                                                    VisitorStatus current) {
        if (current != null && TERMINAL.contains(current)) {
            return Optional.empty();
        }
        VisitorStatus target = CASCADE.get(requestStatus);
        if (target == null || target == current) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    public static boolean isTerminal(VisitorStatus status) {
        return status != null && TERMINAL.contains(status);
    }

    /** For the exhaustiveness test — every request status that causes a cascade (AC-6). */
    public static Set<RequestStatus> cascadingStates() {
        return Set.copyOf(CASCADE.keySet());
    }

    /** For the exhaustiveness test — every visitor status classified as terminal. */
    public static Set<VisitorStatus> terminalStatuses() {
        return Set.copyOf(TERMINAL);
    }
}
