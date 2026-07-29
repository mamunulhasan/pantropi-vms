package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;
import com.pantropi.vms.domain.visitor.RequestStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * The approval queue an FM Admin works through (US-07.3.1, US-07.3.2) — FR-VMS-02 (SRS B1).
 *
 * <p>What it contributes is everything a raw query parameter must survive before it reaches SQL: the
 * page bound (which {@link PageRequest} makes impossible to omit), a status resolved to a real
 * value, and a date range that makes sense.
 *
 * <h2>The default is pending; a status is an explicit opt-in</h2>
 * US-07.3.1 AC-3 requires a decided request to leave the queue, and US-07.3.2 AC-1 offers a status
 * filter. Read together, the only reading in which both mean something: <strong>the queue shows
 * {@code submitted} unless the caller asks for something else</strong>. Loading the queue never
 * shows decided requests — AC-3 holds — while an approver who explicitly asks for approved ones is
 * making a different request, which is what a filter is for.
 *
 * <p>The alternative reading, that a status filter narrows within {@code submitted}, makes the
 * parameter useless: it would either match everything or nothing. This one is recorded rather than
 * assumed, because the two ACs do pull against each other and a later reader deserves to know it
 * was noticed.
 *
 * <p>Which rows are visible is not decided here; the store takes that from the scoping policy, so
 * this class has no tenant condition to get wrong (US-07.3.2 AC-5).
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class PendingApprovals {

    /** What the queue means when nobody says otherwise (US-07.3.1 AC-3). */
    public static final RequestStatus DEFAULT_STATUS = RequestStatus.SUBMITTED;

    /**
     * Long enough for any real name, short enough that the pattern cannot itself become the load.
     */
    public static final int MAX_SEARCH = 100;

    private final ApprovalQueueStore queue;

    public PendingApprovals(ApprovalQueueStore queue) {
        this.queue = queue;
    }

    /** The unfiltered queue, as US-07.3.1 defined it. */
    public ApprovalQueueStore.Page list(int page, int size) {
        return list(null, null, null, null, null, page, size);
    }

    /**
     * @param status   optional status name, case-insensitive; defaults to {@code submitted}
     * @param search   optional substring of a visitor or host name
     * @throws UnknownStatus     if the status is not one this system has
     * @throws InvalidDateRange  if {@code visitFrom} is after {@code visitTo}
     * @throws SearchTooLong     if the search term exceeds {@link #MAX_SEARCH}
     */
    public ApprovalQueueStore.Page list(String status, UUID tenantId, Instant visitFrom,
                                        Instant visitTo, String search, int page, int size) {
        if (visitFrom != null && visitTo != null && visitFrom.isAfter(visitTo)) {
            // Rejected rather than swapped. Silently correcting it would answer a different question
            // from the one asked, and the caller would never learn their client is sending it wrong.
            throw new InvalidDateRange(visitFrom, visitTo);
        }
        return queue.pending(new ApprovalQueueStore.Filter(
                parseStatus(status), tenantId, visitFrom, visitTo, cleanSearch(search),
                new PageRequest(page, size)));
    }

    private static RequestStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return DEFAULT_STATUS;
        }
        String wanted = status.trim();
        for (RequestStatus candidate : RequestStatus.values()) {
            if (candidate.dbValue().equalsIgnoreCase(wanted)
                    || candidate.name().equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        // Not silently ignored (T-07.3.2.2): a dropped filter answers ?status=aproved with the whole
        // queue, which reads as "nothing is waiting" when in fact everything is.
        throw new UnknownStatus(wanted);
    }

    private static String cleanSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String trimmed = search.trim();
        if (trimmed.length() > MAX_SEARCH) {
            throw new SearchTooLong(MAX_SEARCH);
        }
        return trimmed;
    }

    public static final class UnknownStatus extends IllegalArgumentException {
        public UnknownStatus(String ignoredValue) {
            // The offending value is deliberately absent. T-07.3.2.2 asks that filter values not
            // reach access logs, and this message becomes a response body and a log line — echoing
            // arbitrary caller input into both is how a filter parameter ends up somewhere it was
            // never meant to be. Naming the valid values is what actually helps the caller anyway.
            super("Unknown status; expected one of "
                    + java.util.Arrays.stream(RequestStatus.values()).map(RequestStatus::dbValue)
                            .collect(java.util.stream.Collectors.joining(", ")));
        }
    }

    public static final class InvalidDateRange extends IllegalArgumentException {
        public InvalidDateRange(Instant from, Instant to) {
            super("The date range starts at " + from + ", after it ends at " + to);
        }
    }

    public static final class SearchTooLong extends IllegalArgumentException {
        public SearchTooLong(int max) {
            super("A search term must be at most " + max + " characters");
        }
    }
}
