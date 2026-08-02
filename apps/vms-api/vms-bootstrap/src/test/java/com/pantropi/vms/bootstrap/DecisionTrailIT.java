package com.pantropi.vms.bootstrap;

import com.pantropi.vms.infrastructure.identity.Pbkdf2PasswordHasher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INTEGRATION TEST for US-07.4.3 — the decision trail (FR-VMS-02, SRS B1; FR-AUD-01 via TDD §4.6),
 * over real HTTP against real PostgreSQL.
 *
 * <p>Two of these can only be demonstrated against a database. AC-3 asks that the audit table be
 * append-only <em>at the database level, not merely by convention</em>, which means the test has to
 * try the UPDATE itself rather than assert that no code path issues one. AC-5 asks that a failing
 * audit write roll back the state change, which needs a real transaction to roll back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class DecisionTrailIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID hostA;

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
        seed();
        r.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + pg.getPort() + "/postgres");
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) pg.close();
    }

    @TestConfiguration
    static class DataSourceConfig {
        @Bean DataSource dataSource() { return pg.getPostgresDatabase(); }
    }

    // ---- AC-3: append-only, enforced by the database ----

    @Test
    @DisplayName("AC-3: an audit row cannot be updated or deleted — by anyone, including the "
            + "superuser this test connects as")
    void auditRowsAreImmutable() throws Exception {
        String id = submit();
        post("/api/v1/visitor-requests/" + id + "/reject", "{\"reason\":\"Host on leave\"}",
                token("fmadmin"));
        String auditId = scalar("SELECT id::text FROM vms.audit_logs"
                + " WHERE action='visitor_request.reject' AND entity_id='" + id + "'");

        // Not "no code path does this" — the attempt is made directly, with the highest privilege
        // available, and the database refuses it.
        assertThatThrownBy(() -> execute("UPDATE vms.audit_logs SET action='tampered'"
                + " WHERE id=" + auditId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> execute("DELETE FROM vms.audit_logs WHERE id=" + auditId))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("append-only");

        assertThat(scalar("SELECT action FROM vms.audit_logs WHERE id=" + auditId))
                .isEqualTo("visitor_request.reject");
    }

    @Test
    @DisplayName("AC-3: the refusal is loud, not silent — a rejected UPDATE never looks like it "
            + "worked")
    void tamperingFailsLoudly() throws Exception {
        String id = submit();
        post("/api/v1/visitor-requests/" + id + "/cancel", null, token("tenantuser"));

        // A RULE ... DO INSTEAD NOTHING would make this succeed while changing nothing, which is the
        // worst of both: the tamperer believes it worked and the reader sees no trace of the
        // attempt. A trigger that raises is the difference.
        assertThatThrownBy(() -> execute("UPDATE vms.audit_logs SET after_state='{}'::jsonb"
                + " WHERE entity_id='" + id + "'"))
                .isInstanceOf(SQLException.class);

        assertThat(scalar("SELECT after_state->>'status' FROM vms.audit_logs"
                + " WHERE action='visitor_request.cancel' AND entity_id='" + id + "'"))
                .isEqualTo("cancelled");
    }

    @Test
    @DisplayName("inserting is still allowed — append-only means append, not read-only")
    void insertsStillWork() throws Exception {
        String before = scalar("SELECT count(*) FROM vms.audit_logs");
        submitAndApprove();
        assertThat(Long.parseLong(scalar("SELECT count(*) FROM vms.audit_logs")))
                .isGreaterThan(Long.parseLong(before));
    }

    // ---- AC-1, AC-2, AC-4: the trail ----

    @Test
    @DisplayName("AC-1/AC-4: the history is chronological and names actor, action, time and reason")
    void historyIsOrderedAndComplete() throws Exception {
        String id = submit();
        patch(id, "{\"purpose\":\"Rescheduled\"}", token("tenantuser"));
        post("/api/v1/visitor-requests/" + id + "/approve", "{\"note\":\"Cleared with security\"}",
                token("fmadmin"));
        post("/api/v1/visitor-requests/" + id + "/cancel", null, token("tenantuser"));

        var res = get("/api/v1/visitor-requests/" + id + "/history", token("sysadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        String body = res.body();
        // Chronological: a sequence is read forwards when reconstructing what happened.
        assertThat(body.indexOf("visitor_request.submit"))
                .isLessThan(body.indexOf("visitor_request.amend"));
        assertThat(body.indexOf("visitor_request.amend"))
                .isLessThan(body.indexOf("visitor_request.approve"));
        assertThat(body.indexOf("visitor_request.approve"))
                .isLessThan(body.indexOf("visitor_request.cancel"));

        assertThat(body).contains("Tina Tenant").contains("Frances Manager");
        // AC-1: the reason where applicable.
        assertThat(body).contains("Cleared with security");
        assertThat(body).contains("beforeState").contains("afterState").contains("\"at\"");
    }

    @Test
    @DisplayName("AC-2: no visitor personal data appears anywhere in the trail")
    void trailCarriesNoVisitorPii() throws Exception {
        String email = "trail-" + UUID.randomUUID() + "@example.test";
        String id = submitWithVisitor("Ada Lovelace", email, "+8801712345678");
        post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"));

        var res = get("/api/v1/visitor-requests/" + id + "/history", token("sysadmin"));

        assertThat(res.body()).doesNotContain("Ada Lovelace").doesNotContain(email)
                .doesNotContain("8801712345678");
        assertThat(res.body()).contains("visitorCount");
    }

    @Test
    @DisplayName("an id nothing ever happened to has an empty history, not a 404")
    void unknownIdHasEmptyHistory() throws Exception {
        var res = get("/api/v1/visitor-requests/" + UUID.randomUUID() + "/history",
                token("sysadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body().trim()).isEqualTo("[]");
    }

    // ---- AC-5: a decision that cannot be audited does not happen ----

    @Test
    @DisplayName("AC-5: when the audit write fails, the state change rolls back with it")
    void unauditableDecisionDoesNotHappen() throws Exception {
        String id = submit();

        // Force the failure at the database, on the real path, rather than by substituting a fake
        // that throws — the claim being tested is about the transaction, so the failure has to
        // happen inside it.
        // NOT VALID: the constraint governs new inserts without being checked against the rows
        // earlier tests already wrote, which is the whole point — this is about the next write.
        execute("ALTER TABLE vms.audit_logs ADD CONSTRAINT tmp_reject_approvals"
                + " CHECK (action <> 'visitor_request.approve') NOT VALID");
        try {
            var res = post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"));

            assertThat(res.statusCode()).isEqualTo(500);
            // The decision did not happen: no status change, and no outbox event either, because
            // all three writes share one transaction.
            assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                    .isEqualTo("submitted");
            assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                    + "' AND event_type='VisitorRequestApproved'")).isEqualTo("0");
        } finally {
            execute("ALTER TABLE vms.audit_logs DROP CONSTRAINT tmp_reject_approvals");
        }

        // And once the obstruction is gone the same decision succeeds, so the test proved a
        // rollback rather than a request that was never viable.
        assertThat(post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"))
                .statusCode()).isEqualTo(200);
    }

    // ---- AC-6: who may read it ----

    @Test
    @DisplayName("AC-6: an FM Admin who takes decisions cannot read the trail of them")
    void approverCannotReadTheTrail() throws Exception {
        String id = submitAndApprove();

        var res = get("/api/v1/visitor-requests/" + id + "/history", token("fmadmin"));

        // Reading back who decided what is oversight, not part of deciding.
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%audit.view%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("AC-6: a tenant cannot read it either, and an unauthenticated call is 401")
    void tenantAndAnonymousAreRefused() throws Exception {
        String id = submitAndApprove();

        assertThat(get("/api/v1/visitor-requests/" + id + "/history", token("tenantuser"))
                .statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/visitor-requests/" + id + "/history", null).statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("audit.view is granted to SYSTEM_ADMIN and to nobody else")
    void auditViewIsNarrowlyGranted() throws Exception {
        assertThat(scalar("""
                SELECT string_agg(r.code, ',' ORDER BY r.code)
                  FROM vms.role_permissions rp
                  JOIN vms.roles r ON r.id = rp.role_id
                  JOIN vms.permissions p ON p.id = rp.permission_id
                 WHERE p.code = 'audit.view'""")).isEqualTo("SYSTEM_ADMIN");
    }

    // ---- helpers ----

    private String submitAndApprove() throws Exception {
        String id = submit();
        assertThat(post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"))
                .statusCode()).isEqualTo(200);
        return id;
    }

    private String submit() throws Exception {
        return submitWithVisitor("Guest", null, null);
    }

    private String submitWithVisitor(String name, String email, String phone) throws Exception {
        Instant from = Instant.now().plus(30, ChronoUnit.DAYS);
        String visitor = "{\"fullName\":\"" + name + "\""
                + (email == null ? "" : ",\"email\":\"" + email + "\"")
                + (phone == null ? "" : ",\"phone\":\"" + phone + "\"") + "}";
        var res = post("/api/v1/visitor-requests",
                "{\"hostId\":\"" + hostA + "\",\"scheduledFrom\":\"" + from + "\","
                        + "\"scheduledTo\":\"" + from.plus(2, ChronoUnit.HOURS) + "\","
                        + "\"purpose\":\"Quarterly review\",\"visitors\":[" + visitor + "]}",
                token("tenantuser"));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    // ---- US-02.5.1: the trail across every entity, not one request ----

    @Test
    @DisplayName("the audit list returns entries beyond visitor requests, newest first")
    void auditListSpansEntities() throws Exception {
        String id = submit();
        post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"));
        // A refused call writes an entry of an entirely different entity type. That is exactly the
        // sort of row JdbcAuditTrail had been recording all along with no endpoint to read it back
        // — the drill-down only ever answered for one visitor request.
        get("/api/v1/admin/audit", token("tenantuser"));

        var res = get("/api/v1/admin/audit?size=100", token("sysadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("visitor_request.approve");
        assertThat(res.body()).contains("authorization.denied").contains("\"entityType\":\"security\"");
        // Newest first: a log is opened to see what just happened, unlike a decision trail, which
        // is read forwards to reconstruct a sequence.
        int approve = res.body().indexOf("visitor_request.approve");
        int denial = res.body().indexOf("authorization.denied");
        assertThat(denial).isLessThan(approve);
    }

    @Test
    @DisplayName("filters narrow by action and entity type, and the counts follow")
    void auditFiltersNarrow() throws Exception {
        String id = submit();
        post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"));

        var all = get("/api/v1/admin/audit?size=100", token("sysadmin"));
        var narrowed = get("/api/v1/admin/audit?action=visitor_request.approve&size=100",
                token("sysadmin"));

        assertThat(narrowed.statusCode()).isEqualTo(200);
        assertThat(narrowed.body()).contains("visitor_request.approve");
        assertThat(narrowed.body()).doesNotContain("visitor_request.submit");
        assertThat(total(narrowed)).isLessThanOrEqualTo(total(all));

        var byEntity = get("/api/v1/admin/audit?entityType=visitor_request&size=100",
                token("sysadmin"));
        assertThat(byEntity.body()).contains("visitor_request");
    }

    @Test
    @DisplayName("a window with no entries answers an empty page, not everything")
    void auditWindowIsExclusiveAtTheTop() throws Exception {
        submit();
        // Entirely in the past: `to` is exclusive, so nothing written now can fall inside it.
        var res = get("/api/v1/admin/audit?from=2000-01-01T00:00:00Z&to=2000-01-02T00:00:00Z",
                token("sysadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(total(res)).isZero();
    }

    @Test
    @DisplayName("the vocabulary offers what the trail actually contains")
    void auditVocabularyIsDerivedFromTheData() throws Exception {
        String id = submit();
        post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"));

        var res = get("/api/v1/admin/audit/vocabulary", token("sysadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("visitor_request.approve").contains("visitor_request");
    }

    @Test
    @DisplayName("audit.view is required — an approver and a tenant are both refused")
    void auditListNeedsThePermission() throws Exception {
        // FM_ADMIN can decide requests and read one request's history; neither implies the log.
        assertThat(get("/api/v1/admin/audit", token("fmadmin")).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/audit", token("tenantuser")).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/audit", null).statusCode()).isEqualTo(401);
    }

    private static long total(HttpResponse<String> res) {
        String body = res.body();
        int i = body.indexOf("\"totalElements\":") + 16;
        int j = i;
        while (j < body.length() && Character.isDigit(body.charAt(j))) {
            j++;
        }
        return Long.parseLong(body.substring(i, j));
    }

    private String token(String username) throws Exception {
        String password = switch (username) {
            case "tenantuser" -> "tenant-password-1234";
            case "sysadmin" -> "sys-admin-password-123";
            default -> "fm-admin-password-123";
        };
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send("GET", path, null, bearer);
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send("POST", path, body, bearer);
    }

    private HttpResponse<String> patch(String id, String body, String bearer) throws Exception {
        return send("PATCH", "/api/v1/visitor-requests/" + id, body, bearer);
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static void seed() throws Exception {
        var hasher = new Pbkdf2PasswordHasher();
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String building = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.buildings (id, code, name) VALUES ('" + building
                    + "','B1','Tower')");
            String floor = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.floors (id, building_id, code, name) VALUES ('" + floor
                    + "','" + building + "','F1','Floor 1')");

            tenantA = UUID.randomUUID();
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + tenantA
                    + "','TA','Tenant A','" + floor + "')");
            hostA = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostA + "','" + tenantA + "','Host A', true)");

            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");
            String sysRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            user(s, hasher, "tenantuser", "Tina Tenant", "tenant-password-1234", tenantRole,
                    tenantA.toString());
            user(s, hasher, "fmadmin", "Frances Manager", "fm-admin-password-123", fmRole, null);
            user(s, hasher, "sysadmin", "Sam Admin", "sys-admin-password-123", sysRole, null);
        }
    }

    private static void user(Statement s, Pbkdf2PasswordHasher hasher, String username,
                             String fullName, String password, String roleId, String tenantId)
            throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " tenant_id, is_active) VALUES ('" + UUID.randomUUID() + "','" + username + "','"
                + username + "@ex.com','" + fullName + "','"
                + hasher.hash(password.toCharArray()) + "','" + roleId + "',"
                + (tenantId == null ? "NULL" : "'" + tenantId + "'") + ", true)");
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
