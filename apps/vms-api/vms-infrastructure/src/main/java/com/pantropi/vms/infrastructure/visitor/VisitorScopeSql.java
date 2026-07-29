package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.domain.identity.ScopeFilter;

import java.util.List;

/**
 * Turns a {@link ScopeFilter} into the SQL fragment that expresses it on the visitor tables
 * (US-03.4.1, T-03.4.1.2).
 *
 * <p>The translation lives here rather than in the policy because the same rule is a different
 * column on every table: "own tenant" is {@code tenant_id} on {@code vms.visitor_requests}, and a
 * join through the request on {@code vms.visitors}. A policy that emitted SQL would have to know
 * every table it might ever apply to.
 *
 * <p>{@code DENY_ALL} becomes {@code FALSE} rather than an omitted clause. A filter that is
 * <em>absent</em> and one that <em>matches nothing</em> read the same at a call site and mean
 * opposite things, and the failure mode of getting it wrong is a query that returns everything.
 */
final class VisitorScopeSql {

    /** A predicate and its arguments, ready to append to a {@code WHERE}. */
    record Clause(String sql, List<Object> args) {

        /**
         * The predicate with its leading {@code AND}, for appending to a query written as a text
         * block.
         *
         * <p>This exists because {@code """ … WHERE id = ? AND """ + clause.sql()} does not work: a
         * Java text block strips the trailing whitespace from every line, so the result is
         * {@code ANDTRUE}. That shipped once in {@code findById} and was written again in the
         * approval queue within a fortnight, which is the signal that the separator should not be
         * something each call site has to remember.
         */
        String and() {
            return " AND " + sql;
        }
    }

    private VisitorScopeSql() {
    }

    /**
     * @param tenantColumn the column on the table being queried that holds the tenant id
     * @param receptionColumn the column holding the reception id, or null when the table has none
     */
    static Clause on(ScopeFilter filter, String tenantColumn, String receptionColumn) {
        return switch (filter) {
            case ScopeFilter.Unrestricted ignored -> new Clause("TRUE", List.of());
            case ScopeFilter.DenyAll ignored -> new Clause("FALSE", List.of());
            case ScopeFilter.OwnTenant own ->
                    tenantColumn == null
                            ? new Clause("FALSE", List.of())
                            : new Clause(tenantColumn + " = ?", List.of((Object) own.tenantId()));
            case ScopeFilter.OwnReception own ->
                    // A table with no reception column cannot express this restriction, so it
                    // cannot honour it — and a restriction that cannot be honoured must not be
                    // quietly dropped.
                    receptionColumn == null
                            ? new Clause("FALSE", List.of())
                            : new Clause(receptionColumn + " = ?",
                                    List.of((Object) own.receptionId()));
        };
    }
}
