package com.pantropi.vms.application.visitor.usecase;

import java.time.Instant;
import java.util.List;
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
 * @param note   the reason given: optional on an approval, mandatory on a rejection
 * @param issued what became of each visitor's pass (US-09.1.2). Empty on every rejection and on an
 *               approval that issued nothing — the approver still needs to be told which of their
 *               visitors ended up without a working credential, so this is reported rather than
 *               logged and forgotten.
 */
public record RequestDecision(UUID requestId, String status, UUID decidedBy, Instant decidedAt,
                              String note, List<IssuedPass> issued) {

    public RequestDecision {
        issued = issued == null ? List.of() : List.copyOf(issued);
    }

    /** A decision that minted nothing: every rejection, and an approval with issuance switched off. */
    public RequestDecision(UUID requestId, String status, UUID decidedBy, Instant decidedAt,
                           String note) {
        this(requestId, status, decidedBy, decidedAt, note, List.of());
    }

    /** @param outcome a {@code CredentialIssuance.Outcome} name — the port's vocabulary, not a new one */
    public record IssuedPass(UUID visitorId, String outcome) {}

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
