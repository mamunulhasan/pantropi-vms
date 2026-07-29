package com.pantropi.vms.application.visitor.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads the recorded decision trail for one request (US-07.4.3, T-07.4.3.3) — FR-VMS-02 (SRS B1),
 * FR-AUD-01 (TDD §4.6).
 *
 * <p>Read-only by design, and there is no write method here at all: entries reach the trail as a
 * side effect of the decision that caused them, inside its transaction. A port that could append to
 * the trail independently would allow an entry with no corresponding state change — a record of
 * something that did not happen.
 */
public interface DecisionTrail {

    /**
     * Every recorded entry for this request, oldest first.
     *
     * <p>Chronological rather than newest-first: this is read to reconstruct a sequence of events
     * months later, and a sequence reads forwards.
     */
    List<Entry> forRequest(UUID requestId);

    /**
     * @param actor       the display name of whoever acted, or null if the account has since been
     *                    removed — {@code user_id} is {@code ON DELETE SET NULL}, and the entry
     *                    outlives the account
     * @param beforeState the projection captured before the change; null on a creation
     */
    record Entry(long id, Instant at, String action, UUID actorId, String actor, String beforeState,
                 String afterState) {}
}
