package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.application.visitor.port.HostRepository;
import com.pantropi.vms.domain.identity.ScopeFilter;
import com.pantropi.vms.domain.identity.ScopedEntity;
import com.pantropi.vms.domain.visitor.Host;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for the host directory (US-10.1.1, T-10.1.1.1).
 *
 * <p><strong>The scope predicate is appended to every statement, including the writes.</strong>
 * A scoped read is the obvious case, but an update that only filtered on {@code id} would let one
 * tenant rename or deactivate another's staff by guessing a uuid — so the {@code WHERE} on
 * {@code update} carries the same clause as the {@code SELECT}, and a caller outside the scope
 * gets zero rows rather than an exception, which the use case reports as "not found" (AC-4).
 *
 * <p>The clause itself comes from {@link VisitorScopeSql}, not from an inline condition here.
 * `ScopingRulesTest` fails the build if this class reads a scope-sensitive type without depending
 * on the policy, which is what stops a later edit quietly writing its own isolation rule.
 *
 * <p>{@code vms.hosts} has no reception column: a host belongs to a tenant, and a receptionist's
 * reach over them is resolved by translating their reception to the tenants on its floor — that
 * translation lives in {@link VisitorScopeSql} and is shared with every other scoped table.
 */
public final class JdbcHostRepository implements HostRepository {

    private static final String COLUMNS = "id, tenant_id, full_name, email, phone, is_active";

    private static final RowMapper<Host> ROW = (rs, i) -> Host.stored(
            rs.getObject("id", UUID.class),
            rs.getObject("tenant_id", UUID.class),
            rs.getString("full_name"),
            rs.getString("email"),
            rs.getString("phone"),
            rs.getBoolean("is_active"));

    private final JdbcTemplate jdbc;
    private final ScopePolicy policy;

    public JdbcHostRepository(JdbcTemplate jdbc, ScopePolicy policy) {
        this.jdbc = jdbc;
        this.policy = policy;
    }

    /** The predicate for this request's principal. Never a condition written inline here. */
    private ScopeFilter scope() {
        return policy.filterFor(ScopedEntity.HOST);
    }

    @Override
    public void save(Host host) {
        // The insert is guarded by the scope too: `WHERE EXISTS (SELECT 1 ... )` over the tenant
        // the host claims, so a caller cannot file staff under an organisation they cannot see.
        VisitorScopeSql.Clause clause = VisitorScopeSql.on(scope(), "t.id", null);
        List<Object> args = new ArrayList<>();
        args.add(host.id());
        args.add(host.tenantId());
        args.add(host.fullName());
        args.add(host.emailValue());
        args.add(host.phoneValue());
        args.add(host.active());
        args.add(host.tenantId());
        args.addAll(clause.args());

        int rows = jdbc.update("""
                INSERT INTO vms.hosts (id, tenant_id, full_name, email, phone, is_active)
                SELECT ?, ?, ?, ?, ?, ?
                WHERE EXISTS (SELECT 1 FROM vms.tenants t WHERE t.id = ?
                """ + clause.and() + ")", args.toArray());

        if (rows == 0) {
            throw new IllegalStateException("host insert refused by scope");
        }
    }

    @Override
    public Optional<Host> findById(UUID id) {
        VisitorScopeSql.Clause clause = VisitorScopeSql.on(scope(), "tenant_id", null);
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(clause.args());

        return jdbc.query("SELECT " + COLUMNS + " FROM vms.hosts WHERE id = ?" + clause.and(),
                        ROW, args.toArray())
                .stream().findFirst();
    }

    @Override
    public boolean update(Host host) {
        VisitorScopeSql.Clause clause = VisitorScopeSql.on(scope(), "tenant_id", null);
        List<Object> args = new ArrayList<>();
        args.add(host.fullName());
        args.add(host.emailValue());
        args.add(host.phoneValue());
        args.add(host.active());
        args.add(host.id());
        args.addAll(clause.args());

        // tenant_id is never in the SET list: a host cannot be moved between organisations, and
        // leaving the column out is what makes that impossible rather than merely unintended.
        return jdbc.update("""
                UPDATE vms.hosts
                   SET full_name = ?, email = ?, phone = ?, is_active = ?, updated_at = now()
                 WHERE id = ?
                """ + clause.and(), args.toArray()) > 0;
    }

    @Override
    public Page list(Query query) {
        VisitorScopeSql.Clause clause = VisitorScopeSql.on(scope(), "tenant_id", null);
        StringBuilder where = new StringBuilder("WHERE 1 = 1").append(clause.and());
        List<Object> args = new ArrayList<>(clause.args());

        if (query.active() != null) {
            where.append(" AND is_active = ?");
            args.add(query.active());
        }
        if (query.search() != null && !query.search().isBlank()) {
            // Escaped so a term of "%" cannot turn the filter into "everything".
            where.append(" AND full_name ILIKE ? ESCAPE '\\'");
            args.add("%" + query.search().trim()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.hosts " + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(query.size());
        pageArgs.add(query.page() * query.size());
        List<Host> content = jdbc.query(
                "SELECT " + COLUMNS + " FROM vms.hosts " + where
                        + " ORDER BY full_name, id LIMIT ? OFFSET ?",
                ROW, pageArgs.toArray());

        return new Page(content, total == null ? 0L : total, query.page(), query.size());
    }
}
