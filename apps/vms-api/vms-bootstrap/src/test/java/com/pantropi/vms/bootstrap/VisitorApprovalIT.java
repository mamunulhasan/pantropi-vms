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
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.4.1 — an FM Admin approves a visitor request (FR-VMS-02, SRS B1), over
 * real HTTP against real PostgreSQL with the full V1–V10 schema.
 *
 * <p>The test that matters most is {@link #concurrentApprovalsProduceOneWinner()}: AC-6 asks for two
 * simultaneous approvals to yield one success, one 409 and <em>exactly one</em> outbox row, because a
 * second event would have EPIC-09 issue a second credential for the same visit. That cannot be
 * demonstrated with fakes — it needs two real transactions contending for the same row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false",
                // US-09.1.2: approval mints the passes, so this class needs an ACS to mint them
                // against. The simulator is the only one there is, and everything asserted about a
                // credential here closes at "done against simulator" (ADR-0002, TODO-02).
                "vms.acs.mode=simulator",
                // The email adapter writes files; keep them inside build/ rather than the repo root.
                "vms.notification.outbox-dir=build/test-notifications"
        })
class VisitorApprovalIT {

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

    // ---- AC-1, AC-2, AC-3, AC-4 ----

    @Test
    @DisplayName("AC-1/AC-2/AC-3/AC-4: approval writes the decision, the visitors, one event and "
            + "one audit entry")
    void approvalWritesEverything() throws Exception {
        String visitorEmail = "ada-" + UUID.randomUUID() + "@example.test";
        String id = submit(visitorEmail, futureWindow());
        String fmAdminId = scalar("SELECT id FROM vms.users WHERE username='fmadmin'");

        var res = approve(id, "{\"note\":\"Cleared with building security\"}",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"approved\"");

        // AC-1: the request and every visitor moved.
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("approved");
        assertThat(scalar("SELECT approved_by FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(fmAdminId);
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE request_id='" + id
                + "' AND status <> 'approved'")).isEqualTo("0");

        // AC-4: the note is stored, and decided_at was set from the application clock.
        assertThat(scalar("SELECT decision_reason FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("Cleared with building security");
        assertThat(scalar("SELECT decided_at FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isNotNull();

        // AC-2: exactly one event, carrying ids and the window but no visitor personal data.
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestApproved'")).isEqualTo("1");
        String payload = scalar("SELECT payload::text FROM vms.domain_events WHERE aggregate_id='"
                + id + "' AND event_type='VisitorRequestApproved'");
        assertThat(payload).contains("visitorIds").contains("scheduledFrom").contains("approvedBy");
        assertThat(payload).contains(
                scalar("SELECT id::text FROM vms.visitors WHERE request_id='" + id + "'"));
        assertThat(payload).doesNotContain("Ada").doesNotContain(visitorEmail)
                .doesNotContain("880100000000").doesNotContain("Analytical");

        // AC-3: one audit entry with before/after and no PII — and not the note text either.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.approve' AND entity_id='" + id + "'"))
                .isEqualTo("1");
        assertThat(scalar("SELECT before_state::text FROM vms.audit_logs"
                + " WHERE action='visitor_request.approve' AND entity_id='" + id + "'"))
                .contains("submitted");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE entity_id='" + id
                + "' AND after_state::text LIKE '%" + visitorEmail + "%'")).isEqualTo("0");
        // US-07.4.3 AC-1 asks the audit row to carry "the decision reason where applicable", which
        // supersedes this story's choice to record only that a note was given. An approval note is
        // a decision reason, so the trail now holds it — as it always did for a rejection.
        assertThat(scalar("SELECT after_state->>'decisionReason' FROM vms.audit_logs"
                + " WHERE action='visitor_request.approve' AND entity_id='" + id + "'"))
                .isEqualTo("Cleared with building security");
    }

    @Test
    @DisplayName("US-09.1.2 AC-1: approving mints a pass for the visitor and emails them")
    void approvalIssuesThePass() throws Exception {
        String visitorEmail = "pass-" + UUID.randomUUID() + "@example.test";
        String id = submit(visitorEmail, futureWindow());
        String visitorId = scalar("SELECT id::text FROM vms.visitors WHERE request_id='" + id + "'");

        var res = approve(id, null, token("fmadmin", "fm-admin-password-123"));
        assertThat(res.statusCode()).isEqualTo(200);

        // AC-1: a credential per visitor, active, carrying the reference the simulator returned.
        assertThat(scalar("SELECT count(*) FROM vms.credentials WHERE visitor_id='" + visitorId + "'"))
                .isEqualTo("1");
        assertThat(scalar("SELECT state::text FROM vms.credentials WHERE visitor_id='" + visitorId + "'"))
                .isEqualTo("active");
        assertThat(scalar("SELECT acs_credential_id FROM vms.credentials WHERE visitor_id='"
                + visitorId + "'")).isNotNull();

        // The window is the approved window, not a default and not now-plus-something.
        assertThat(scalar("SELECT (c.valid_from = r.scheduled_from AND c.valid_to = r.scheduled_to)::text"
                + " FROM vms.credentials c JOIN vms.visitor_requests r ON r.id='" + id + "'"
                + " WHERE c.visitor_id='" + visitorId + "'")).isEqualTo("true");

        // The decision response tells the approver what became of each pass (AC-5's reporting half).
        assertThat(res.body()).contains("\"issued\"").contains("ISSUED").contains(visitorId);

        // US-09.6.1: the visitor is emailed, at their own address, and the log holds a body_ref
        // rather than the body.
        assertThat(scalar("SELECT count(*) FROM vms.notification_logs WHERE visitor_id='"
                + visitorId + "' AND channel='email' AND delivery_status='sent'")).isEqualTo("1");
        assertThat(scalar("SELECT recipient FROM vms.notification_logs WHERE visitor_id='"
                + visitorId + "'")).isEqualTo(visitorEmail);

        // The QR payload opens a barrier. It is not in the audit trail and not in the email log.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs a, vms.credentials c"
                + " WHERE c.visitor_id='" + visitorId + "' AND c.qr_payload IS NOT NULL"
                + " AND a.after_state::text LIKE '%' || c.qr_payload || '%'")).isEqualTo("0");
    }

    @Test
    @DisplayName("AC-4: the note is optional — approving with no body at all still works")
    void noteIsOptional() throws Exception {
        String id = submit("x-" + UUID.randomUUID() + "@example.test", futureWindow());

        var res = approve(id, null, token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("approved");
        assertThat(scalar("SELECT decision_reason FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isNull();
    }

    // ---- AC-6: the concurrent approval ----

    @Test
    @DisplayName("AC-6: two simultaneous approvals give one 200, one 409 and exactly one event")
    void concurrentApprovalsProduceOneWinner() throws Exception {
        String id = submit("race-" + UUID.randomUUID() + "@example.test", futureWindow());
        String tokenOne = token("fmadmin", "fm-admin-password-123");
        String tokenTwo = token("fmadmin2", "fm-admin-password-456");

        // Both threads are released at the same instant so the two transactions genuinely overlap.
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(
                    pool.submit(() -> raceOne(go, id, tokenOne)),
                    pool.submit(() -> raceOne(go, id, tokenTwo)));
            go.countDown();

            List<Integer> codes = List.of(results.get(0).get(30, TimeUnit.SECONDS),
                    results.get(1).get(30, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("approved");
        // The single-event guarantee: a second event here is a second ACS credential downstream.
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestApproved'")).isEqualTo("1");
        // The loser's audit entry rolled back with its transaction.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.approve' AND entity_id='" + id + "'"))
                .isEqualTo("1");
    }

    private int raceOne(CountDownLatch go, String id, String bearer) throws Exception {
        go.await();
        return approve(id, "{\"note\":\"race\"}", bearer).statusCode();
    }

    // ---- AC-5, AC-7 and the negative cases ----

    @Test
    @DisplayName("AC-5: approving an already-approved request is 409 and writes nothing further")
    void secondApprovalConflicts() throws Exception {
        String id = submit("twice-" + UUID.randomUUID() + "@example.test", futureWindow());
        String bearer = token("fmadmin", "fm-admin-password-123");
        assertThat(approve(id, null, bearer).statusCode()).isEqualTo(200);

        var res = approve(id, null, bearer);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("already_decided").contains("approved");
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestApproved'")).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.approve' AND entity_id='" + id + "'"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("AC-7: a window that has already elapsed is 422, and nothing is written")
    void elapsedWindowIsUnprocessable() throws Exception {
        String id = submit("past-" + UUID.randomUUID() + "@example.test",
                "\"scheduledFrom\":\"2020-01-01T09:00:00Z\","
                        + "\"scheduledTo\":\"2020-01-01T11:00:00Z\"");

        var res = approve(id, null, token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(res.body()).contains("window_elapsed");
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestApproved'")).isEqualTo("0");
    }

    @Test
    @DisplayName("a note longer than the bound is 400 and the request stays submitted")
    void overlongNoteRejected() throws Exception {
        String id = submit("long-" + UUID.randomUUID() + "@example.test", futureWindow());

        var res = approve(id, "{\"note\":\"" + "x".repeat(1001) + "\"}",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");
    }

    @Test
    @DisplayName("a tenant holding visitor.request cannot approve: 403, and the denial is audited")
    void tenantCannotApprove() throws Exception {
        String id = submit("denied-" + UUID.randomUUID() + "@example.test", futureWindow());

        var res = approve(id, null, token("tenantuser", "tenant-password-1234"));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.approve%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("an unknown request is 404, and so is one the caller may not see")
    void unknownRequestIsNotFound() throws Exception {
        var res = approve(UUID.randomUUID().toString(), null,
                token("fmadmin", "fm-admin-password-123"));
        assertThat(res.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("unauthenticated approval is 401")
    void unauthenticatedRejected() throws Exception {
        var res = approve(UUID.randomUUID().toString(), null, null);
        assertThat(res.statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

    /** A window comfortably ahead of the test run, so AC-7 never fires by accident. */
    private static String futureWindow() {
        Instant from = Instant.now().plus(30, ChronoUnit.DAYS);
        return "\"scheduledFrom\":\"" + from + "\",\"scheduledTo\":\""
                + from.plus(2, ChronoUnit.HOURS) + "\"";
    }

    private String submit(String visitorEmail, String window) throws Exception {
        var res = post("/api/v1/visitor-requests", """
                {"hostId":"%s",%s,"purpose":"Quarterly review",
                 "visitors":[{"fullName":"Ada Lovelace","email":"%s","phone":"+880100000000",
                              "company":"Analytical Ltd"}]}
                """.formatted(hostA, window, visitorEmail),
                token("tenantuser", "tenant-password-1234"));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    private HttpResponse<String> approve(String id, String body, String bearer) throws Exception {
        return post("/api/v1/visitor-requests/" + id + "/approve", body, bearer);
    }

    private String token(String u, String p) throws Exception {
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
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

            user(s, hasher, "tenantuser", "tenant-password-1234", tenantRole, tenantA.toString());
            // Two FM Admins, because AC-6 is about two people deciding the same request. Neither is
            // tenant-scoped: FM_ADMIN is building-wide (US-03.4.1).
            user(s, hasher, "fmadmin", "fm-admin-password-123", fmRole, null);
            user(s, hasher, "fmadmin2", "fm-admin-password-456", fmRole, null);
        }
    }

    private static void user(Statement s, Pbkdf2PasswordHasher hasher, String username,
                             String password, String roleId, String tenantId) throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " tenant_id, is_active) VALUES ('" + UUID.randomUUID() + "','" + username + "','"
                + username + "@ex.com','" + username + "','"
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
