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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.4.2 — an FM Admin rejects a request with a mandatory reason
 * (FR-VMS-02, SRS B1), over real HTTP against real PostgreSQL.
 *
 * <p>AC-2 asks specifically for the 400 to be proven by a direct API call that bypasses the UI, which
 * is what these are. AC-6 is covered by round-tripping a hostile reason: it must survive the
 * {@code jsonb} audit payload intact and come back through the API as data, never as markup.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class VisitorRejectionIT {

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

    // ---- AC-1, AC-3, AC-4 ----

    @Test
    @DisplayName("AC-1/AC-3: rejection writes the decision, cancels the visitors, and records the "
            + "reason in both the row and the audit")
    void rejectionWritesEverything() throws Exception {
        String visitorEmail = "ada-" + UUID.randomUUID() + "@example.test";
        String id = submit(visitorEmail);
        String fmAdminId = scalar("SELECT id FROM vms.users WHERE username='fmadmin'");

        var res = reject(id, "{\"reason\":\"Host is on leave that week\"}",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"rejected\"");

        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("rejected");
        assertThat(scalar("SELECT approved_by FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(fmAdminId);
        assertThat(scalar("SELECT decision_reason FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("Host is on leave that week");
        assertThat(scalar("SELECT decided_at FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isNotNull();

        // AC-1: every visitor cancelled.
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE request_id='" + id
                + "' AND status <> 'cancelled'")).isEqualTo("0");

        // AC-3: the audit carries the reason — the only immutable record of the grounds.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.reject' AND entity_id='" + id + "'"))
                .isEqualTo("1");
        assertThat(scalar("SELECT after_state->>'decisionReason' FROM vms.audit_logs"
                + " WHERE action='visitor_request.reject' AND entity_id='" + id + "'"))
                .isEqualTo("Host is on leave that week");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE entity_id='" + id
                + "' AND after_state::text LIKE '%" + visitorEmail + "%'")).isEqualTo("0");
    }

    @Test
    @DisplayName("AC-3/AC-4: exactly one rejection event, ids only, and nothing EPIC-09 consumes")
    void oneEventAndNoCredentialPath() throws Exception {
        String visitorEmail = "evt-" + UUID.randomUUID() + "@example.test";
        String id = submit(visitorEmail);

        reject(id, "{\"reason\":\"Building closed\"}", token("fmadmin", "fm-admin-password-123"));

        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestRejected'")).isEqualTo("1");
        // AC-4: a rejected request never reaches credential orchestration.
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestApproved'")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM vms.credentials")).isEqualTo("0");

        String payload = scalar("SELECT payload::text FROM vms.domain_events WHERE aggregate_id='"
                + id + "' AND event_type='VisitorRequestRejected'");
        assertThat(payload).contains("visitorIds").contains("decidedBy");
        // The reason stays out of the event; it is operator prose, and an event leaves for a bus.
        assertThat(payload).doesNotContain("Building closed")
                .doesNotContain("Ada").doesNotContain(visitorEmail);
    }

    // ---- AC-2: the reason is mandatory at the API ----

    @Test
    @DisplayName("AC-2: a blank, whitespace-only, absent or bodyless reason is 400 and nothing "
            + "changes — proven by direct API calls that bypass the UI")
    void reasonIsMandatoryAtTheApi() throws Exception {
        String bearer = token("fmadmin", "fm-admin-password-123");
        String[] bodies = {
                "{\"reason\":\"\"}",
                "{\"reason\":\"   \"}",
                "{\"reason\":\"\\t\\n\"}",
                "{\"reason\":null}",
                "{}",
                null                              // no body at all
        };

        for (String body : bodies) {
            String id = submit("blank-" + UUID.randomUUID() + "@example.test");

            var res = reject(id, body, bearer);

            assertThat(res.statusCode()).as("body=%s", body).isEqualTo(400);
            assertThat(res.body()).contains("reason_required");
            assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                    .isEqualTo("submitted");
            assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                    + "'AND event_type='VisitorRequestRejected'")).isEqualTo("0");
            assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                    + " WHERE action='visitor_request.reject' AND entity_id='" + id + "'"))
                    .isEqualTo("0");
        }
    }

    @Test
    @DisplayName("a reason longer than the bound is 400 and the request stays submitted")
    void overlongReasonRejected() throws Exception {
        String id = submit("long-" + UUID.randomUUID() + "@example.test");

        var res = reject(id, "{\"reason\":\"" + "x".repeat(1001) + "\"}",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");
    }

    // ---- AC-6: stored as text, all the way through ----

    @Test
    @DisplayName("AC-6: a reason containing script markup, quotes, backslashes and newlines is "
            + "stored verbatim and survives the jsonb audit payload")
    void hostileReasonRoundTrips() throws Exception {
        String id = submit("xss-" + UUID.randomUUID() + "@example.test");
        // JSON-encoded on the wire; the stored value is the decoded text below.
        String wire = "<script>alert('xss')</script> \\\"quoted\\\" C:\\\\temp\\nsecond line";
        String stored = "<script>alert('xss')</script> \"quoted\" C:\\temp\nsecond line";

        var res = reject(id, "{\"reason\":\"" + wire + "\"}",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(200);
        // Stored as text, not sanitised: mangling it here would corrupt legitimate reasons, and the
        // defence against a stored script belongs at render time in the tenant's browser.
        assertThat(scalar("SELECT decision_reason FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(stored);
        // AC-6 also means the audit payload must still be valid jsonb — this read would fail if the
        // raw newline had been concatenated in unescaped.
        assertThat(scalar("SELECT after_state->>'decisionReason' FROM vms.audit_logs"
                + " WHERE action='visitor_request.reject' AND entity_id='" + id + "'"))
                .isEqualTo(stored);
        // The response is JSON, and the markup comes back as data inside a JSON string.
        assertThat(res.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(res.body()).contains("<script>");
    }

    // ---- AC-5 and the other negatives ----

    @Test
    @DisplayName("AC-5: rejecting an approved request is 409 — reversing an approval is a "
            + "cancellation, not a rejection")
    void approvedCannotBeRejected() throws Exception {
        String id = submit("approved-" + UUID.randomUUID() + "@example.test");
        String bearer = token("fmadmin", "fm-admin-password-123");
        assertThat(approve(id, bearer).statusCode()).isEqualTo(200);

        var res = reject(id, "{\"reason\":\"On reflection, no\"}", bearer);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("already_decided").contains("approved");
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("approved");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.reject' AND entity_id='" + id + "'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("AC-5: a second rejection is 409 and leaves the first decision intact")
    void secondRejectionConflicts() throws Exception {
        String id = submit("twice-" + UUID.randomUUID() + "@example.test");
        String bearer = token("fmadmin", "fm-admin-password-123");
        assertThat(reject(id, "{\"reason\":\"Host on leave\"}", bearer).statusCode()).isEqualTo(200);

        var res = reject(id, "{\"reason\":\"Different reason\"}", bearer);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(scalar("SELECT decision_reason FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("Host on leave");
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestRejected'")).isEqualTo("1");
    }

    @Test
    @DisplayName("a tenant holding visitor.request cannot reject: 403, and the denial is audited")
    void tenantCannotReject() throws Exception {
        String id = submit("denied-" + UUID.randomUUID() + "@example.test");

        var res = reject(id, "{\"reason\":\"I withdraw it\"}",
                token("tenantuser", "tenant-password-1234"));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.approve%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("an unknown request is 404, and an unauthenticated call is 401")
    void notFoundAndUnauthenticated() throws Exception {
        String body = "{\"reason\":\"Host on leave\"}";
        assertThat(reject(UUID.randomUUID().toString(), body,
                token("fmadmin", "fm-admin-password-123")).statusCode()).isEqualTo(404);
        assertThat(reject(UUID.randomUUID().toString(), body, null).statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

    private String submit(String visitorEmail) throws Exception {
        Instant from = Instant.now().plus(30, ChronoUnit.DAYS);
        var res = post("/api/v1/visitor-requests", """
                {"hostId":"%s","scheduledFrom":"%s","scheduledTo":"%s","purpose":"Quarterly review",
                 "visitors":[{"fullName":"Ada Lovelace","email":"%s","phone":"+880100000000",
                              "company":"Analytical Ltd"}]}
                """.formatted(hostA, from, from.plus(2, ChronoUnit.HOURS), visitorEmail),
                token("tenantuser", "tenant-password-1234"));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    private HttpResponse<String> reject(String id, String body, String bearer) throws Exception {
        return post("/api/v1/visitor-requests/" + id + "/reject", body, bearer);
    }

    private HttpResponse<String> approve(String id, String bearer) throws Exception {
        return post("/api/v1/visitor-requests/" + id + "/approve", null, bearer);
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
            user(s, hasher, "fmadmin", "fm-admin-password-123", fmRole, null);
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
