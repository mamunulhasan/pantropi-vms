package com.pantropi.vms.domain.visitor;

import java.time.Instant;
import java.util.Objects;

/**
 * A scheduled visit window (US-07.1.1, T-07.1.1.1) — FR-VMS-01 (SRS B1).
 *
 * <p>Invariant: the end is strictly after the start. Enforced at construction, so an invalid window
 * cannot exist in memory and the database {@code CHECK (scheduled_to > scheduled_from)} is a
 * backstop that is never reached in normal operation (AC-4).
 */
public record TimeWindow(Instant from, Instant to) {

    public TimeWindow {
        Objects.requireNonNull(from, "window start is required");
        Objects.requireNonNull(to, "window end is required");
        if (!to.isAfter(from)) {
            throw new InvalidTimeWindow(from, to);
        }
    }

    public boolean contains(Instant moment) {
        return !moment.isBefore(from) && moment.isBefore(to);
    }

    /** Raised when a window would have a non-positive duration. */
    public static final class InvalidTimeWindow extends IllegalArgumentException {
        public InvalidTimeWindow(Instant from, Instant to) {
            super("The visit window must end after it starts (from=" + from + ", to=" + to + ")");
        }
    }
}
