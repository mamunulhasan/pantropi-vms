package com.pantropi.vms.application.acs.port;

/**
 * Everything that can go wrong at the ACS boundary, in three kinds (T-11.1.1.3).
 *
 * <p><strong>Sealed on purpose.</strong> A caller deciding what to do about a failure is deciding
 * between retry, give up, and alert a human — and a sealed hierarchy makes {@code switch} over
 * those exhaustive, so a fourth kind added later fails to compile at every site that must now
 * consider it. An open exception hierarchy would let it slip through as "something else".
 *
 * <p>No ACS error code, status line or vendor message appears in any of these. Callers never see
 * one (ADR-0002 decision 2): the adapter's job is to decide which of the three a given wire
 * failure is, and everything above the boundary reasons in these terms. {@code message} is for a
 * log; the <em>type</em> is the contract.
 */
public sealed class AcsFailure extends RuntimeException {

    private AcsFailure(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The call may succeed if repeated — a timeout, a refused connection, a 5xx, a rate limit.
     *
     * <p>This is the kind the retry and outbox machinery exists for. Nothing above the port
     * retries by hand.
     */
    public static final class Transient extends AcsFailure {
        public Transient(String message) {
            this(message, null);
        }

        public Transient(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * It will not succeed if repeated — a rejected request, an unknown credential, a refused
     * authentication.
     *
     * <p>Retrying a permanent failure turns one bad request into a queue of them, which is how a
     * broken integration becomes an outage.
     */
    public static final class Permanent extends AcsFailure {
        public Permanent(String message) {
            this(message, null);
        }

        public Permanent(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * ACS answered, and the answer could not be understood.
     *
     * <p>Its own kind rather than a flavour of the other two, because it means something different
     * operationally: transient says wait, permanent says stop, malformed says <em>the contract we
     * built against is wrong</em>. With TODO-02 still open and the adapter written against a
     * contract we have not seen, this is the failure most likely to be telling the truth about a
     * mistaken assumption — so it must be distinguishable rather than buried.
     */
    public static final class MalformedResponse extends AcsFailure {
        public MalformedResponse(String message) {
            this(message, null);
        }

        public MalformedResponse(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
