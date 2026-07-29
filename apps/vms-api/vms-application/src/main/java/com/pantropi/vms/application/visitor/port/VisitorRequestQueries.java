package com.pantropi.vms.application.visitor.port;

import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.domain.visitor.RequestStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The tenant's view of its own requests (US-07.6.1, T-07.6.1.1) — FR-VMS-01 (SRS B1).
 *
 * <h2>This is the phase's primary IDOR surface</h2>
 * Every method here is scoped by the caller's own tenant, and the scope comes from the policy inside
 * the adapter — never from a parameter a caller supplies, and never from a condition written in a
 * controller. A scope passed <em>in</em> can be passed wrong: null, another tenant's id, or omitted
 * by a caller written next year who did not know it mattered. A scope resolved from the authenticated
 * principal at the point of query cannot be.
 *
 * <p>T-07.6.1.1 phrases the safeguard as "a fitness test asserts tenant-facing repository methods
 * cannot be invoked without the scope parameter". There is no scope parameter to omit, which is the
 * stronger version of the same guarantee; {@code ScopingRulesTest} already fails the build for any
 * infrastructure class that reads scope-sensitive data without consulting the policy.
 *
 * <p>The scope predicate is applied <strong>before</strong> any caller-supplied filter (AC-2), so a
 * filter narrows what the tenant may see and can never widen it.
 */
public interface VisitorRequestQueries {

    /** The caller's own requests, newest first, narrowed by the filter. */
    Page list(Filter filter);

    /**
     * One request in full, or empty when it does not exist <em>or</em> is not the caller's (AC-4).
     *
     * <p>The two are one answer on purpose. A caller must not be able to tell a foreign id from a
     * missing one, or the endpoint becomes an oracle for which requests exist elsewhere in the
     * building.
     */
    Optional<Detail> detail(UUID id);

    /**
     * @param status  optional; already validated to a known value by the caller, so an unrecognised
     *                one is refused rather than silently dropping the condition
     * @param visitFrom optional lower bound on the visit window, inclusive
     * @param visitTo   optional upper bound on the visit window, exclusive
     */
    record Filter(RequestStatus status, Instant visitFrom, Instant visitTo, PageRequest page) {}

    /** A row of the tenant's list: enough to know whether the visitor is expected (AC-1). */
    record Summary(UUID id, String status, String host, Instant scheduledFrom, Instant scheduledTo,
                   int visitorCount, Instant submittedAt, Instant decidedAt,
                   String decisionReason) {}

    /**
     * The detail view, including the decision and why (AC-3).
     *
     * @param decidedBy the approver's display name only — never their username, email or id
     *                  (T-07.6.1.2); a tenant needs to know a decision was taken by a named person,
     *                  not to be handed an identifier they could use elsewhere
     */
    record Detail(UUID id, String status, String host, String purpose, Instant scheduledFrom,
                  Instant scheduledTo, Instant submittedAt, String decidedBy, Instant decidedAt,
                  String decisionReason, List<VisitorLine> visitors) {}

    /** The tenant's own guests, by name and status — they supplied these people themselves. */
    record VisitorLine(String fullName, String status) {}

    record Page(List<Summary> content, long totalElements, int page, int size) {}
}
