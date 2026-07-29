package com.pantropi.vms.bootstrap;

import com.pantropi.vms.application.identity.port.ScopeContext;
import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.domain.identity.ScopeFilter;
import com.pantropi.vms.domain.identity.ScopedEntity;
import com.pantropi.vms.infrastructure.identity.RequestScopeContext;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-03.4.1 — the scope context against a real database (T-03.4.1.1).
 *
 * <p>No Spring context: the behaviour under test is thread-locality and clearing, which a servlet
 * thread would hide rather than reveal — the test thread cannot observe another thread's context.
 *
 * <p>End-to-end isolation over HTTP arrives with Milestone G, which adds the first scoped read
 * route. What this proves is that the seam resolves correctly, and — the part that matters most —
 * that it does not leak between requests on a pooled thread.
 */
class ScopeContextIT {

    private static EmbeddedPostgres pg;
    private static DataSource ds;
    private static UUID tenantUser;
    private static UUID receptionUser;
    private static UUID adminUser;
    private static UUID deactivatedUser;
    private static UUID tenantId;
    private static UUID receptionId;

    private RequestScopeContext context;

    @BeforeAll
    static void migrate() throws Exception {
        pg = EmbeddedPostgres.start();
        ds = pg.getPostgresDatabase();
        Flyway.configure().dataSource(ds).schemas("vms").defaultSchema("vms")
                .cleanDisabled(true).load().migrate();
        seed();
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) pg.close();
    }

    @AfterEach
    void clearBetweenTests() {
        if (context != null) {
            context.clear();
        }
    }

    private RequestScopeContext fresh() {
        context = new RequestScopeContext(new JdbcTemplate(ds));
        return context;
    }

    @Test
    @DisplayName("AC-1: the scope is resolved from vms.users, not from the token")
    void resolvesFromTheDatabase() {
        RequestScopeContext ctx = fresh();
        ctx.begin(tenantUser, "TENANT");

        ScopeContext.Scope scope = ctx.current().orElseThrow();

        assertThat(scope.userId()).isEqualTo(tenantUser);
        assertThat(scope.roleCode()).isEqualTo("TENANT");
        assertThat(scope.tenantId()).isEqualTo(tenantId);
        assertThat(scope.receptionId()).isNull();
    }

    @Test
    @DisplayName("AC-1: a receptionist resolves their reception")
    void resolvesReception() {
        RequestScopeContext ctx = fresh();
        ctx.begin(receptionUser, "FLOOR_RECEPTIONIST");

        assertThat(ctx.current().orElseThrow().receptionId()).isEqualTo(receptionId);
    }

    @Test
    @DisplayName("AC-1: no scope has been begun, so there is nothing to read")
    void emptyBeforeBegin() {
        assertThat(fresh().current()).isEmpty();
    }

    @Test
    @DisplayName("AC-1: clearing leaves nothing behind — this is what stops cross-request leakage")
    void clearRemovesTheScope() {
        RequestScopeContext ctx = fresh();
        ctx.begin(tenantUser, "TENANT");
        assertThat(ctx.current()).isPresent();

        ctx.clear();

        assertThat(ctx.current()).isEmpty();
    }

    @Test
    @DisplayName("AC-1: a second thread sees nothing while the first holds a scope")
    void scopeIsPerThread() throws Exception {
        RequestScopeContext ctx = fresh();
        ctx.begin(tenantUser, "TENANT");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Boolean> otherThreadSawSomething = pool.submit(() -> ctx.current().isPresent());

        assertThat(otherThreadSawSomething.get()).isFalse();
        pool.shutdown();
    }

    @Test
    @DisplayName("AC-1: a pooled thread carries no residual scope into the next request")
    void noResidueOnAPooledThread() throws Exception {
        RequestScopeContext ctx = fresh();
        // One thread, reused — exactly the servlet container's arrangement, and the reason
        // afterCompletion clears rather than the handler doing it.
        ExecutorService pool = Executors.newSingleThreadExecutor();

        UUID firstSaw = pool.submit(() -> {
            ctx.begin(tenantUser, "TENANT");
            try {
                return ctx.current().orElseThrow().tenantId();
            } finally {
                ctx.clear();
            }
        }).get();

        boolean secondSawAnything = pool.submit(() -> ctx.current().isPresent()).get();

        assertThat(firstSaw).isEqualTo(tenantId);
        assertThat(secondSawAnything)
                .as("the next request on this thread inherited the previous one's scope")
                .isFalse();
        pool.shutdown();
    }

    @Test
    @DisplayName("a deactivated user resolves to no tenant and no reception")
    void deactivatedUserHasNoScope() {
        RequestScopeContext ctx = fresh();
        ctx.begin(deactivatedUser, "TENANT");

        ScopeContext.Scope scope = ctx.current().orElseThrow();

        assertThat(scope.tenantId()).isNull();
        // ...and the policy turns that into seeing nothing, rather than into no restriction.
        assertThat(new ScopePolicy(ctx).filterFor(ScopedEntity.VISITOR_REQUEST))
                .isEqualTo(ScopeFilter.DENY_ALL);
    }

    // ---- the policy over a real resolution ----

    @Test
    @DisplayName("a tenant user is filtered to their own tenant, end to end")
    void policyOverRealScope() {
        RequestScopeContext ctx = fresh();
        ctx.begin(tenantUser, "TENANT");

        assertThat(new ScopePolicy(ctx).filterFor(ScopedEntity.VISITOR_REQUEST))
                .isEqualTo(new ScopeFilter.OwnTenant(tenantId));
    }

    @Test
    @DisplayName("an administrator is unrestricted, end to end")
    void adminIsUnrestricted() {
        RequestScopeContext ctx = fresh();
        ctx.begin(adminUser, "FM_ADMIN");

        assertThat(new ScopePolicy(ctx).filterFor(ScopedEntity.VISITOR_REQUEST))
                .isInstanceOf(ScopeFilter.Unrestricted.class);
    }

    @Test
    @DisplayName("with no scope begun the policy denies, so an unauthenticated read sees nothing")
    void noScopeDeniesAll() {
        assertThat(new ScopePolicy(fresh()).filterFor(ScopedEntity.VISITOR_REQUEST))
                .isEqualTo(ScopeFilter.DENY_ALL);
    }

    // ---- seed ----

    private static void seed() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO vms.buildings (code, name) VALUES ('SCOPE-B', 'Scope Building')""");
            String building = single(s, "SELECT id FROM vms.buildings WHERE code='SCOPE-B'");
            s.execute("INSERT INTO vms.floors (building_id, code, name) VALUES ('"
                    + building + "', 'SCOPE-F', 'Scope Floor')");
            String floor = single(s, "SELECT id FROM vms.floors WHERE code='SCOPE-F'");
            s.execute("INSERT INTO vms.receptions (floor_id, code, name) VALUES ('"
                    + floor + "', 'SCOPE-R', 'Scope Reception')");
            s.execute("INSERT INTO vms.tenants (code, name, floor_id) VALUES ('SCOPE-T', "
                    + "'Scope Tenant', '" + floor + "')");

            tenantId = UUID.fromString(single(s, "SELECT id FROM vms.tenants WHERE code='SCOPE-T'"));
            receptionId = UUID.fromString(
                    single(s, "SELECT id FROM vms.receptions WHERE code='SCOPE-R'"));

            tenantUser = insert(s, "scope-tenant", "TENANT", tenantId, null, true);
            receptionUser = insert(s, "scope-reception", "FLOOR_RECEPTIONIST", null, receptionId,
                    true);
            adminUser = insert(s, "scope-admin", "FM_ADMIN", null, null, true);
            deactivatedUser = insert(s, "scope-gone", "TENANT", tenantId, null, false);
        }
    }

    private static UUID insert(Statement s, String username, String roleCode, UUID tenant,
                               UUID reception, boolean active) throws Exception {
        UUID id = UUID.randomUUID();
        String role = single(s, "SELECT id FROM vms.roles WHERE code='" + roleCode + "'");
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " tenant_id, reception_id, is_active) VALUES ('" + id + "', '" + username + "', '"
                + username + "@ex.com', '" + username + "', 'x', '" + role + "', "
                + (tenant == null ? "NULL" : "'" + tenant + "'") + ", "
                + (reception == null ? "NULL" : "'" + reception + "'") + ", " + active + ")");
        return id;
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }
}
