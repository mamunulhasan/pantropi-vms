package com.pantropi.vms.application.shared;

/**
 * A page of results, with the bound applied at construction (US-07.3.1, AC-5).
 *
 * <p>AC-5 asks that a request for 10,000 rows be clamped rather than executed. The clamp lives in
 * the compact constructor, so an unclamped {@code PageRequest} cannot be built at all — a query that
 * takes one of these is bounded whether or not whoever wrote it remembered. Written as a check in
 * the controller, or in the adapter as the user list does it, it is a step the next endpoint has to
 * repeat, and the failure mode of forgetting is an unbounded query reachable over HTTP.
 *
 * <p>Clamping rather than rejecting is deliberate and is what T-07.3.1.3 asks for: an oversized page
 * is far more often a client with an optimistic default than an attack, and a 400 helps nobody. The
 * applied size travels back on the response so the caller can see what it actually got.
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public record PageRequest(int page, int size) {

    /**
     * The ceiling a caller cannot raise.
     *
     * <p>100 rows of the approval queue is a working screenful several times over; the number exists
     * to bound the query, not to express a UI preference.
     */
    public static final int MAX_SIZE = 100;

    public static final int DEFAULT_SIZE = 20;

    public PageRequest {
        page = Math.max(0, page);
        size = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    /** Rows to skip — as a long, because {@code page * size} on ints is an overflow waiting. */
    public long offset() {
        return (long) page * size;
    }
}
