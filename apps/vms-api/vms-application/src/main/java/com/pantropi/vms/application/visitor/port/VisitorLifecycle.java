package com.pantropi.vms.application.visitor.port;

import com.pantropi.vms.domain.visitor.VisitorStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Moving one visitor through their own lifecycle (US-12.2.1, US-12.2.2) —
 * {@code TDD-DERIVED} FR-ENT-10 (TDD §4.4).
 *
 * <p>Separate from {@link VisitorRequestRepository} on purpose. That one saves a decision taken on
 * a request and cascades it to everybody on it; this moves a single person because of something
 * that happened to them. Sharing a port would make it easy to write the second while meaning the
 * first.
 */
public interface VisitorLifecycle {

    /**
     * Compare-and-set on the status the caller decided from.
     *
     * <p>The expected status is passed in for the same reason the approval path passes one: two
     * receptionists can have the same visitor open, and the database is where they meet. A caller
     * that read {@code approved} and writes {@code checked_in} must not win if somebody already
     * moved that person — {@code false} means it lost and the caller re-reads.
     *
     * @param at the timestamp written to {@code checked_in_at} or {@code checked_out_at}; which
     *           column depends on {@code next}, because a status and its timestamp are one fact
     * @return false when no row matched the expected status
     */
    boolean transition(UUID visitorId, VisitorStatus expected, VisitorStatus next, Instant at);
}
