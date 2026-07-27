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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.1.1 — a tenant submits a visitor entry request (FR-VMS-01, SRS B1),
 * over real HTTP against real PostgreSQL with the full V1–V7 schema.
 *
 * <p>Seeds two tenants with a host each, a TENANT user in tenant A (holds {@code visitor.request}
 * via V7), and a FLOOR_RECEPTIONIST who does not.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class VisitorRequestIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID hostA;
    private static UUID hostB;

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
        seed();
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + pg.getPort() + "/postgres");
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

    @Test
    @DisplayName("AC-1/AC-2/AC-3: a tenant submits; request, visitor, event and audit are all written")
    void submissionCreatesEverything() throws Exception {
        String token = accessToken("tenantuser", "tenant-password-1234");
        String visitorEmail = "ada-" + UUID.randomUUID() + "@example.test";

        var res = post("/api/v1/visitor-requests", """
                {"hostId":"%s","scheduledFrom":"2026-08-01T09:00:00Z",
                 "scheduledTo":"2026-08-01T11:00:00Z","purpose":"Quarterly review",
                 "visitors":[{"fullName":"Ada Lovelace","email":"%s","phone":"+880100000000",
                              "company":"Analytical Ltd"}]}
                """.formatted(hostA, visitorEmail), token);

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.headers().firstValue("Location")).isPresent();
        String id = field(res.body(), "id");

        // AC-1: request row, correct tenant/submitter/status/kind
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");
        assertThat(scalar("SELECT visit_kind::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("pre_scheduled");
        assertThat(scalar("SELECT tenant_id FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(tenantA.toString());
        assertThat(scalar("SELECT requested_by FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(scalar("SELECT id FROM vms.users WHERE username='tenantuser'"));

        // AC-1: visitor row, pending
        assertThat(scalar("SELECT status::text FROM vms.visitors WHERE request_id='" + id + "'"))
                .isEqualTo("pending");
        assertThat(scalar("SELECT full_name FROM vms.visitors WHERE request_id='" + id + "'"))
                .isEqualTo("Ada Lovelace");

        // AC-2: exactly one event, carrying no visitor personal data
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestSubmitted'")).isEqualTo("1");
        String payload = scalar("SELECT payload::text FROM vms.domain_events WHERE aggregate_id='"
                + id + "'");
        assertThat(payload).contains("requestId").contains("tenantId").contains("visitorCount");
        assertThat(payload).doesNotContain("Ada").doesNotContain(visitorEmail)
                .doesNotContain("880100000000").doesNotContain("Analytical");

        // AC-3: audit entry, PII-redacted
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='visitor_request.submit'"
                + " AND entity_id='" + id + "'")).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE after_state::text LIKE '%"
                + visitorEmail + "%'")).isEqualTo("0");
    }

    @Test
    @DisplayName("AC-4: an inverted window is a 400 and nothing is persisted")
    void invalidWindowRejected() throws Exception {
        String token = accessToken("tenantuser", "tenant-password-1234");
        String before = scalar("SELECT count(*) FROM vms.visitor_requests");

        var res = post("/api/v1/visitor-requests", """
                {"hostId":"%s","scheduledFrom":"2026-08-01T11:00:00Z",
                 "scheduledTo":"2026-08-01T09:00:00Z",
                 "visitors":[{"fullName":"Ada Lovelace"}]}
                """.formatted(hostA), token);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM vms.domain_events")).isNotNull();
    }

    @Test
    @DisplayName("AC-5: a tenantId supplied in the body is ignored — the caller's own tenant is used")
    void tenantIdCannotBeOverridden() throws Exception {
        String token = accessToken("tenantuser", "tenant-password-1234");

        // Attempt the override: claim tenant B, and B's host.
        var res = post("/api/v1/visitor-requests", """
                {"tenantId":"%s","hostId":"%s","scheduledFrom":"2026-08-02T09:00:00Z",
                 "scheduledTo":"2026-08-02T10:00:00Z","status":"approved",
                 "visitors":[{"fullName":"Mallory"}]}
                """.formatted(tenantB, hostA), token);

        assertThat(res.statusCode()).isEqualTo(201);
        String id = field(res.body(), "id");
        // The supplied tenant was ignored; the request belongs to the caller's own tenant.
        assertThat(scalar("SELECT tenant_id FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(tenantA.toString()).isNotEqualTo(tenantB.toString());
        // The supplied status was ignored too.
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");
    }

    @Test
    @DisplayName("AC-5: a host belonging to another tenant is refused")
    void foreignHostRefused() throws Exception {
        String token = accessToken("tenantuser", "tenant-password-1234");
        var res = post("/api/v1/visitor-requests", """
                {"hostId":"%s","scheduledFrom":"2026-08-03T09:00:00Z",
                 "scheduledTo":"2026-08-03T10:00:00Z","visitors":[{"fullName":"Ada"}]}
                """.formatted(hostB), token);
        assertThat(res.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("AC-6: a user without visitor.request gets 403, nothing persists, denial audited")
    void withoutPermissionForbidden() throws Exception {
        String receptionist = accessToken("receptionist", "reception-password-12");
        String before = scalar("SELECT count(*) FROM vms.visitor_requests");

        var res = post("/api/v1/visitor-requests", """
                {"hostId":"%s","scheduledFrom":"2026-08-04T09:00:00Z",
                 "scheduledTo":"2026-08-04T10:00:00Z","visitors":[{"fullName":"Ada"}]}
                """.formatted(hostA), receptionist);

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isEqualTo(before);
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.request%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("unauthenticated submission is 401")
    void unauthenticatedRejected() throws Exception {
        var res = post("/api/v1/visitor-requests", """
                {"scheduledFrom":"2026-08-05T09:00:00Z","scheduledTo":"2026-08-05T10:00:00Z",
                 "visitors":[{"fullName":"Ada"}]}
                """, null);
        assertThat(res.statusCode()).isEqualTo(401);
    }

    // ---- helpers ----
    private String accessToken(String u, String p) throws Exception {
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://localhost:" + port; }

    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static void seed() throws Exception {
        var hasher = new Pbkdf2PasswordHasher();
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            String building = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.buildings (id, code, name) VALUES ('" + building + "','B1','Tower')");
            String floor = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.floors (id, building_id, code, name) VALUES ('" + floor
                    + "','" + building + "','F1','Floor 1')");

            tenantA = UUID.randomUUID();
            tenantB = UUID.randomUUID();
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + tenantA
                    + "','TA','Tenant A','" + floor + "')");
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + tenantB
                    + "','TB','Tenant B','" + floor + "')");

            hostA = UUID.randomUUID();
            hostB = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostA + "','" + tenantA + "','Host A', true)");
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostB + "','" + tenantB + "','Host B', true)");

            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String floorRole = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                    + " tenant_id, is_active) VALUES ('" + UUID.randomUUID()
                    + "','tenantuser','t@ex.com','Tenant User','"
                    + hasher.hash("tenant-password-1234".toCharArray()) + "','" + tenantRole
                    + "','" + tenantA + "', true)");
            s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                    + " tenant_id, is_active) VALUES ('" + UUID.randomUUID()
                    + "','receptionist','r@ex.com','Receptionist','"
                    + hasher.hash("reception-password-12".toCharArray()) + "','" + floorRole
                    + "','" + tenantA + "', true)");
        }
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next(); return rs.getString(1);
        }
    }
}
