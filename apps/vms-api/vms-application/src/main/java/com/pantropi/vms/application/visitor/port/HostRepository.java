package com.pantropi.vms.application.visitor.port;

import com.pantropi.vms.domain.visitor.Host;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for the host directory (US-10.1.1, T-10.1.1.1).
 *
 * <p><strong>No method here takes a scope, and that is deliberate.</strong> AC-4 requires the
 * cross-tenant refusal to live in the repository, so the adapter obtains the predicate from
 * {@code ScopePolicy} itself rather than trusting a caller to pass the right one — the same shape
 * as {@code VisitorRequestRepository}, and the shape {@code ScopingRulesTest} enforces. A caller
 * outside the scope gets an empty result or a zero-row update, which the use case turns into the
 * same 404 an unknown id produces.
 *
 * <p>There is deliberately <strong>no delete</strong>. A host is referenced by every visit they
 * received; removing the row would orphan those (AC-5). The absence of the method is what makes
 * that structural rather than a rule someone has to remember.
 */
public interface HostRepository {

    /** Insert a new host. The tenant on the host is authoritative; the scope must permit it. */
    void save(Host host);

    /** One host, if the scope can see it. Empty for an unknown id and for another tenant's (AC-4). */
    Optional<Host> findById(UUID id);

    /**
     * Replace a host's details or active state.
     *
     * @return true when a row was updated; false when the scope could not see it, which the caller
     *         reports exactly as it reports an unknown id
     */
    boolean update(Host host);

    /** A page of hosts visible to the scope, newest names first, active and inactive both. */
    Page list(Query query);

    /**
     * @param active null means both — AC-2 requires inactive hosts to remain visible and
     *               distinguishable, not hidden
     */
    record Query(String search, Boolean active, int page, int size) {

        public static final int MAX_SIZE = 100;
        public static final int DEFAULT_SIZE = 20;

        /** Clamps rather than refuses, matching every other list endpoint in the API. */
        public Query {
            page = Math.max(0, page);
            size = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        }
    }

    record Page(List<Host> content, long totalElements, int page, int size) {}
}
