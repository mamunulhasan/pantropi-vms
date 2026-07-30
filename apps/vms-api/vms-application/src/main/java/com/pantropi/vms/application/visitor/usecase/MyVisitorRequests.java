package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.port.VisitorRequestQueries;
import com.pantropi.vms.domain.visitor.RequestStatus;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A tenant's own requests and what happened to them (US-07.6.1, T-07.6.1.2) — FR-VMS-01 (SRS B1).
 *
 * <p>Two jobs, both about turning raw query parameters into something the query cannot be harmed by:
 * the page bound (via {@link PageRequest}) and the status filter.
 *
 * <h2>An unrecognised status is refused, not ignored</h2>
 * The tempting alternative — drop a filter we do not understand — answers a request for
 * {@code ?status=aproved} with the tenant's <em>entire</em> list, which reads as "nothing has been
 * decided yet" when in fact everything has. A filter that silently does not apply is worse than one
 * that fails, because the caller cannot tell.
 *
 * <p>Which rows are visible is not decided here; the adapter takes that from the scoping policy, so
 * this class has no tenant condition to get wrong (AC-6).
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class MyVisitorRequests {

    private final VisitorRequestQueries queries;
    private final AuditTrail audit;

    public MyVisitorRequests(VisitorRequestQueries queries, AuditTrail audit) {
        this.queries = queries;
        this.audit = audit;
    }

    /**
     * @param status  optional status name, case-insensitive
     * @throws UnknownStatus if the status is not one this system has
     */
    public VisitorRequestQueries.Page list(String status, Instant visitFrom, Instant visitTo,
                                           int page, int size) {
        return queries.list(filter(status, visitFrom, visitTo, page, size));
    }

    /**
     * The validator for what {@link #list} would return, without building it (US-07.6.2).
     *
     * <p>Separate from the list so a polling caller can ask "has this changed?" for the cost of a
     * count, and only pay for the list when the answer is yes. Both take the same filter through the
     * same method, so the question and the answer are always about the same rows.
     */
    public String listVersion(String status, Instant visitFrom, Instant visitTo, int page,
                              int size) {
        return queries.listVersion(filter(status, visitFrom, visitTo, page, size));
    }

    private VisitorRequestQueries.Filter filter(String status, Instant visitFrom, Instant visitTo,
                                                int page, int size) {
        return new VisitorRequestQueries.Filter(
                parseStatus(status), visitFrom, visitTo, new PageRequest(page, size));
    }

    /**
     * @throws RequestDecision.NotFound when it does not exist, or belongs to another tenant — one
     *                                  answer for both, so the endpoint is not an existence oracle
     *                                  (AC-4)
     */
    public VisitorRequestQueries.Detail detail(UUID actor, UUID id) {
        VisitorRequestQueries.Detail detail = queries.detail(id)
                .orElseThrow(() -> new RequestDecision.NotFound(id));

        // US-07.3.3 AC-2: this is the read that names people, so the reading of it is itself an
        // event worth recording. Written before the response is handed back, so it stands whether
        // or not the client keeps what it asked for (T-07.3.3.1).
        //
        // The detail is deliberately null, which T-07.3.3.1 asks for: "after_state is null for a
        // read and does not duplicate the PII being audited". A read changed nothing, so there is no
        // state to record — and anything put here would be copied into a table V12 just made
        // append-only, where a mistake cannot be corrected.
        //
        // A visitor count was the tempting thing to include. It is not personal data, but it is also
        // not what the entry is for: the fact worth keeping is that this person read this request at
        // this time, and the request itself still says how many visitors it has.
        audit.record(actor, "visitor_request.view", "visitor_request", id.toString(), null);

        // A read that could not be audited is one nobody can later prove happened, so it is not
        // caught and swallowed here: if the audit write fails, the caller gets an error rather than
        // the personal data.
        return detail;
    }

    private static RequestStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String wanted = status.trim();
        for (RequestStatus candidate : RequestStatus.values()) {
            if (candidate.dbValue().equalsIgnoreCase(wanted)
                    || candidate.name().equalsIgnoreCase(wanted)) {
                return candidate;
            }
        }
        throw new UnknownStatus(wanted);
    }

    /** Names the valid values, so a caller can fix the request without reading the source. */
    public static final class UnknownStatus extends IllegalArgumentException {
        public UnknownStatus(String ignoredValue) {
            // The offending value is deliberately absent. T-07.3.2.2 asks that filter values not
            // reach access logs, and this message becomes a response body and a log line — echoing
            // arbitrary caller input into both is how a filter parameter ends up somewhere it was
            // never meant to be. Naming the valid values is what actually helps the caller anyway.
            super("Unknown status; expected one of "
                    + Arrays.stream(RequestStatus.values()).map(RequestStatus::dbValue)
                            .collect(Collectors.joining(", ")));
        }
    }
}
