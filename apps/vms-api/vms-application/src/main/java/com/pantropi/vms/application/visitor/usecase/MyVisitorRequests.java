package com.pantropi.vms.application.visitor.usecase;

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

    public MyVisitorRequests(VisitorRequestQueries queries) {
        this.queries = queries;
    }

    /**
     * @param status  optional status name, case-insensitive
     * @throws UnknownStatus if the status is not one this system has
     */
    public VisitorRequestQueries.Page list(String status, Instant visitFrom, Instant visitTo,
                                           int page, int size) {
        return queries.list(new VisitorRequestQueries.Filter(
                parseStatus(status), visitFrom, visitTo, new PageRequest(page, size)));
    }

    /**
     * @throws RequestDecision.NotFound when it does not exist, or belongs to another tenant — one
     *                                  answer for both, so the endpoint is not an existence oracle
     *                                  (AC-4)
     */
    public VisitorRequestQueries.Detail detail(UUID id) {
        return queries.detail(id).orElseThrow(() -> new RequestDecision.NotFound(id));
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
        public UnknownStatus(String given) {
            super("Unknown status '" + given + "'; expected one of "
                    + Arrays.stream(RequestStatus.values()).map(RequestStatus::dbValue)
                            .collect(Collectors.joining(", ")));
        }
    }
}
