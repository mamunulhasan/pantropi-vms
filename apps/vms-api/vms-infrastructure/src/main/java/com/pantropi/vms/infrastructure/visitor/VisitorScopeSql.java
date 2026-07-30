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
            case ScopeFilter.OwnReception own -> ownReception(own, tenantColumn, receptionColumn);
        };
    }

    /**
     * A receptionist's scope, translated for the table at hand (US-08.1.3, ADR-0005).
     *
     * <p>On a table with a reception column, it is that column. The visitor tables have none — a
     * request belongs to a tenant, not a desk — so there the restriction is expressed through what
     * "own reception" means for visitor data: <strong>the tenants on the receptionist's own
     * floor</strong>. That is ADR-0005's own-floor rule, and it is what lets a floor receptionist
     * read and maintain the pre-registrations they created (US-08.1.3 AC-5) while a record filed
     * against another floor's tenant stays invisible to them.
     *
     * <p>Until US-08.1.3 this arm answered {@code FALSE} for a missing reception column, which was
     * correct while no receptionist-facing read existed: a restriction that cannot be expressed must
     * not be dropped. It can be expressed after all — through the tenant — so now it is.
     *
     * <p>A table with neither column still answers {@code FALSE}.
     */
    private static Clause ownReception(ScopeFilter.OwnReception own, String tenantColumn,
                                       String receptionColumn) {
        if (receptionColumn != null) {
            return new Clause(receptionColumn + " = ?", List.of((Object) own.receptionId()));
        }
        if (tenantColumn != null) {
            return new Clause(tenantColumn + " IN (SELECT t.id FROM vms.tenants t"
                    + " JOIN vms.receptions r ON r.floor_id = t.floor_id"
                    + " WHERE r.id = ? AND t.is_active = true)",
                    List.of((Object) own.receptionId()));
        }
        return new Clause("FALSE", List.of());
    }
}
