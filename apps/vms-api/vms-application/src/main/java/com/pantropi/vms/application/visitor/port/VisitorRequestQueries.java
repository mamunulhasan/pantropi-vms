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
     * A validator for the list this filter would return (US-07.6.2, T-07.6.2.3).
     *
     * <p>Cheap by construction: a count and a high-water timestamp over the same scoped rows, rather
     * than the list itself with its joins and per-request visitor counts. A status view polling every
     * few seconds asks this question far more often than it needs a new answer.
     *
     * <h2>It includes a discriminator for the caller's scope, and that is the security control</h2>
     * Two tenants can hold the same number of requests last touched at the same instant. If the
     * validator were only "count and timestamp", their lists would share a value, and an intermediary
     * caching by URL could hand one tenant's body to the other on a 304. Folding the scope into the
     * token makes that impossible rather than improbable — which is what T-07.6.2.3 asks to be
     * asserted by a two-tenant test.
     *
     * <p>The filter is part of it too: two different filtered views of the same data are different
     * representations and must not validate against each other.
     *
     * <h2>What it does not notice</h2>
     * A host renamed in master data changes the rendered list without touching any request row, so a
     * poll can serve a stale host name until that request next changes. Stated rather than hidden:
     * covering it would mean joining the host table into the validator and paying for that on every
     * poll, to catch something that happens approximately never on a status screen.
     */
    String listVersion(Filter filter);

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

    /**
     * One named guest (US-07.3.3 AC-1).
     *
     * <p>Name, company, classification and status. Deliberately still no email, phone or
     * {@code id_document_ref}: an approver deciding whether to admit somebody needs to know who is
     * coming and in what capacity, not how to contact them. TODO-13 keeps the document reference out
     * of every endpoint in this phase.
     *
     * <p>The two ids here point in opposite directions, deliberately. The visitor's <em>own</em> id
     * is present because an approver has to be able to act on that visitor — issuing their pass is
     * the whole reason the line exists. The visitor <em>type's</em> id is still absent, and the
     * original argument for that is untouched: a reviewer reads "Contractor", and handing out the
     * type id would only invite a client to resolve it against master data it has no business
     * reading. Exposing an id you can act on is not the same as exposing one you can only probe with.
     *
     * @param visitorType the type's display name, not its id
     */
    record VisitorLine(UUID id, String fullName, String company, String visitorType, String status) {}

    record Page(List<Summary> content, long totalElements, int page, int size) {}
}
