package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.visitor.port.DecisionTrail;

import java.util.List;
import java.util.UUID;

/**
 * The recorded decision trail for one request (US-07.4.3, T-07.4.3.3) — FR-VMS-02 (SRS B1).
 *
 * <p>Thin, and there is nothing here to make it thicker: the trail is whatever was written, and this
 * use case exists so the endpoint depends on an application type rather than reaching for an adapter.
 *
 * <p>An unknown id returns an empty list rather than a not-found. The audit trail answers "what
 * happened to this id", and for an id nothing ever happened to, the honest answer is "nothing" —
 * not "no such thing", which would make the endpoint an existence oracle for a reader who,
 * holding {@code audit.view}, is entitled to ask about any id anyway.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class RequestHistory {

    private final DecisionTrail trail;

    public RequestHistory(DecisionTrail trail) {
        this.trail = trail;
    }

    public List<DecisionTrail.Entry> of(UUID requestId) {
        return trail.forRequest(requestId);
    }
}
