package com.pantropi.vms.domain.visitor;

import java.time.Instant;

/**
 * Whether the person at the desk is appointed, right now (US-12.1.2, T-12.1.2.1) —
 * FR-VMS-04 (SRS B1).
 *
 * <p>The story is explicit about why this exists: a receptionist should not have to interpret a
 * request status, two timestamps and a visitor status under time pressure while somebody waits.
 * Three inputs, one named answer.
 *
 * <p><strong>Order matters, and it is the order a desk cares about.</strong> A duplicate arrival is
 * checked before the window, because someone already inside who presents again is a question about
 * them and not about the clock (AC-5). The request's status is checked next, because a rejected
 * visit is not made acceptable by arriving punctually (AC-2). Only then does the window decide
 * between early, appointed and elapsed.
 *
 * <p>Pure Java: no framework, no clock of its own — {@code now} is passed in, so the boundary
 * instants are testable rather than a matter of when the suite happens to run.
 */
public final class AppointmentConfirmation {

    /** One named outcome per situation. Nothing here means "maybe". */
    public enum Outcome {
        /** Approved, in window, not already arrived. The only outcome that permits issuance. */
        APPOINTED,
        /** The request was never approved, or was rejected or withdrawn (AC-2). */
        NOT_APPOINTED,
        /** Approved, but the window has not opened yet. Not a refusal (AC-3). */
        EARLY,
        /** Approved, but {@code appointment_to} has passed. Extending it is a decision (AC-4). */
        ELAPSED,
        /** Already checked in, inside, or checked out — a possible duplicate arrival (AC-5). */
        ALREADY_ARRIVED
    }

    private final Outcome outcome;
    private final String reason;
    private final Instant windowFrom;
    private final Instant windowTo;

    private AppointmentConfirmation(Outcome outcome, String reason, Instant from, Instant to) {
        this.outcome = outcome;
        this.reason = reason;
        this.windowFrom = from;
        this.windowTo = to;
    }

    /**
     * @param requestStatus the parent request's decision state
     * @param visitorStatus this person's own lifecycle state
     * @param window        the appointment window; null when the visit names none
     */
    public static AppointmentConfirmation evaluate(RequestStatus requestStatus,
                                                   VisitorStatus visitorStatus,
                                                   TimeWindow window,
                                                   Instant now) {
        Instant from = window == null ? null : window.from();
        Instant to = window == null ? null : window.to();

        // AC-5 first: someone who has already been dealt with is a duplicate-arrival question,
        // whatever the clock says. Warning rather than refusing is the point — the desk decides.
        if (visitorStatus == VisitorStatus.CHECKED_IN || visitorStatus == VisitorStatus.INSIDE
                || visitorStatus == VisitorStatus.CHECKED_OUT) {
            return new AppointmentConfirmation(Outcome.ALREADY_ARRIVED,
                    "This visitor is already " + visitorStatus.dbValue().replace('_', ' ') + ".",
                    from, to);
        }

        // AC-2: arriving on time does not make an undecided or refused visit acceptable.
        if (requestStatus != RequestStatus.APPROVED) {
            return new AppointmentConfirmation(Outcome.NOT_APPOINTED,
                    switch (requestStatus) {
                        case SUBMITTED -> "This visit has not been approved yet.";
                        case REJECTED -> "This visit was rejected.";
                        case CANCELLED -> "This visit was withdrawn.";
                        default -> "This visit is not approved.";
                    }, from, to);
        }
        if (visitorStatus == VisitorStatus.CANCELLED || visitorStatus == VisitorStatus.EXPIRED
                || visitorStatus == VisitorStatus.NO_SHOW) {
            return new AppointmentConfirmation(Outcome.NOT_APPOINTED,
                    "This visitor is " + visitorStatus.dbValue().replace('_', ' ') + ".", from, to);
        }

        // A visit with no window is appointed for as long as it is approved: nothing to compare.
        if (from == null || to == null) {
            return new AppointmentConfirmation(Outcome.APPOINTED, null, from, to);
        }
        // Inclusive at both ends. A visitor arriving exactly at the opening instant is on time,
        // and one arriving exactly at the close has not yet missed it — the alternative turns a
        // boundary into a refusal nobody could have predicted.
        if (now.isBefore(from)) {
            return new AppointmentConfirmation(Outcome.EARLY,
                    "The appointment opens later.", from, to);
        }
        if (now.isAfter(to)) {
            return new AppointmentConfirmation(Outcome.ELAPSED,
                    "The appointment window has passed.", from, to);
        }
        return new AppointmentConfirmation(Outcome.APPOINTED, null, from, to);
    }

    public Outcome outcome() {
        return outcome;
    }

    /** Plain language for the desk, or null when the visitor is simply appointed. */
    public String reason() {
        return reason;
    }

    public Instant windowFrom() {
        return windowFrom;
    }

    public Instant windowTo() {
        return windowTo;
    }

    /** The one outcome that lets a receptionist proceed. Checked in one place, not per screen. */
    public boolean mayProceed() {
        return outcome == Outcome.APPOINTED;
    }
}
