package com.pantropi.vms.application.visitor.usecase;

import java.time.Instant;
import java.util.UUID;

/**
 * The outcome of a decision on a visitor request, and the two ways taking one can fail for reasons
 * that are not about the decision itself (US-07.4.1, US-07.4.2).
 *
 * <p>Shared by approval and rejection because they are the same shape and the same failures. Left on
 * {@code ApproveVisitorRequest}, a rejection would throw {@code ApproveVisitorRequest.RequestNotFound}
 * — a stack trace that names the wrong operation, and a controller handler list that reads as though
 * one endpoint's errors were being reused for another.
 *
 * @param note the reason given: optional on an approval, mandatory on a rejection
 */
public record RequestDecision(UUID requestId, String status, UUID decidedBy, Instant decidedAt,
                              String note) {

    /**
     * Absent, or out of the caller's scope — the two are the same answer on purpose, so an id cannot
     * be used to probe for requests belonging to someone else (US-03.4.1).
     */
    public static final class NotFound extends RuntimeException {
        public NotFound(UUID id) {
            super("No visitor request " + id);
        }
    }

    /** Lost the race for a decision (US-07.4.1 AC-6). The current state is worth re-reading, so 409. */
    public static final class DecidedElsewhere extends IllegalStateException {
        public DecidedElsewhere(UUID id) {
            super("Visitor request " + id + " was decided by someone else while you were deciding");
        }
    }
}
