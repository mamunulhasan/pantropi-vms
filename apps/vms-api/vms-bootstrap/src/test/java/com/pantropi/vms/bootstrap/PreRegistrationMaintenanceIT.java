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
 * INTEGRATION TEST for US-08.1.3 — a floor receptionist corrects or withdraws a pre-registration
 * (FR-VMS-03, SRS B1), over real HTTP against real PostgreSQL.
 *
 * <p>Two things only a real database can demonstrate here. AC-5's floor isolation comes from the
 * scope seam — {@code OwnReception} translated to the tenants on the receptionist's floor — so the
 * two-floor case has to run against the real predicate. And AC-3's revocation is triggered by a row
 * in {@code vms.credentials}, which nothing issues yet, so the test inserts one directly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class PreRegistrationMaintenanceIT {

    private static EmbeddedPostgres pg;
    private static UUID floorOneTenant;
    private static UUID hostOnFloorOne;

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

    // ---- AC-1: amend ----

    @Test
    @DisplayName("AC-1: details and window are corrected in place — same visitor row, new values, "
            + "audited without PII")
    void amendPersistsInPlace() throws Exception {
        String visitorId = preRegister("Ada Lovelace");
        Instant newFrom = Instant.now().plus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        var res = patch(visitorId, """
                {"fullName":"Ada King","email":"Ada.King@Example.TEST","appointmentFrom":"%s",
                 "appointmentTo":"%s"}
                """.formatted(newFrom, newFrom.plus(2, ChronoUnit.HOURS)), token("recone"));

        assertThat(res.statusCode()).isEqualTo(200);
        // The same row, corrected — not a replacement with a new id.
        assertThat(scalar("SELECT full_name FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("Ada King");
        assertThat(scalar("SELECT email FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("ada.king@example.test");     // normalised, like a fresh entry
        // The untouched field survived the partial edit.
        assertThat(scalar("SELECT company FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("Analytical Ltd");
        // The window moved on the visitor row too — compared as an epoch, because the database
        // renders timestamptz in the server's zone and a string match would depend on it.
        assertThat(scalar("SELECT extract(epoch FROM appointment_from)::bigint"
                + " FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo(String.valueOf(newFrom.getEpochSecond()));

        // AC-1: audited, before/after, and neither name reaches the trail.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor.amend' AND entity_id='" + visitorId + "'")).isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE entity_id='" + visitorId
                + "' AND (after_state::text LIKE '%Ada King%'"
                + " OR after_state::text LIKE '%ada.king%')")).isEqualTo("0");
    }

    @Test
    @DisplayName("AC-4: a visitor already checked in refuses amendment and cancellation with 409")
    void checkedInVisitorIsUntouchable() throws Exception {
        String visitorId = preRegister("Inside Guest");
        execute("UPDATE vms.visitors SET status='checked_in' WHERE id='" + visitorId + "'");

        var amend = patch(visitorId, "{\"fullName\":\"Sneaky Edit\"}", token("recone"));
        var cancel = post("/api/v1/pre-registrations/" + visitorId + "/cancel", null,
                token("recone"));

        assertThat(amend.statusCode()).isEqualTo(409);
        assertThat(amend.body()).contains("visitor_not_editable").contains("checked_in");
        assertThat(cancel.statusCode()).isEqualTo(409);
        assertThat(scalar("SELECT full_name FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("Inside Guest");
        assertThat(scalar("SELECT status::text FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("checked_in");
    }

    // ---- AC-2: cancel ----

    @Test
    @DisplayName("AC-2: cancelling the only visitor cancels the request and it leaves the queue")
    void cancellingTheOnlyVisitorTakesTheRequest() throws Exception {
        String visitorId = preRegister("Lone Guest");
        String requestId = scalar("SELECT request_id FROM vms.visitors WHERE id='"
                + visitorId + "'");

        var res = post("/api/v1/pre-registrations/" + visitorId + "/cancel", null, token("recone"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"requestCancelled\":true");
        assertThat(scalar("SELECT status::text FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("cancelled");
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='"
                + requestId + "'")).isEqualTo("cancelled");
        assertThat(get("/api/v1/visitor-requests/pending?size=100",
                token("fmadmin")).body()).doesNotContain(requestId);
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + requestId
                + "' AND event_type='VisitorCancelled'")).isEqualTo("1");
    }

    @Test
    @DisplayName("AC-2: cancelling one of two leaves the request and the other visitor standing")
    void cancellingOneOfTwoLeavesTheRest() throws Exception {
        // A tenant-submitted group request on the receptionist's floor: the desk may maintain any
        // visitor filed against its own floor's tenants, however the record arrived.
        String requestId = tenantSubmitsTwo();
        String firstVisitor = scalar("SELECT id FROM vms.visitors WHERE request_id='" + requestId
                + "' ORDER BY created_at LIMIT 1");

        var res = post("/api/v1/pre-registrations/" + firstVisitor + "/cancel", null,
                token("recone"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"requestCancelled\":false");
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='"
                + requestId + "'")).isEqualTo("submitted");
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE request_id='" + requestId
                + "' AND status='pending'")).isEqualTo("1");
    }

    // ---- AC-3: revocation ----

    @Test
    @DisplayName("AC-3: cancelling a visitor who holds a live credential raises exactly one "
            + "revocation signal and audits that cancellation followed issuance")
    void cancellationAfterIssuanceRevokes() throws Exception {
        String visitorId = preRegister("Credentialed Guest");
        String requestId = scalar("SELECT request_id FROM vms.visitors WHERE id='"
                + visitorId + "'");
        // Nothing issues credentials yet (EPIC-09), so the state AC-3 concerns is constructed
        // directly — the check must be real before issuance arrives, not reworked after.
        execute("INSERT INTO vms.credentials (visitor_id, credential_type, valid_from, valid_to,"
                + " state) VALUES ('" + visitorId + "', 'qr', now(), now() + interval '1 day',"
                + " 'active')");

        var res = post("/api/v1/pre-registrations/" + visitorId + "/cancel", null, token("recone"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"revocationRequested\":true");
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + requestId
                + "' AND event_type='CredentialRevocationRequested'")).isEqualTo("1");
        assertThat(scalar("SELECT payload::text FROM vms.domain_events WHERE aggregate_id='"
                + requestId + "' AND event_type='CredentialRevocationRequested'"))
                .contains(visitorId).contains("pre_registration_cancelled");
        assertThat(scalar("SELECT after_state->>'followedIssuance' FROM vms.audit_logs"
                + " WHERE action='visitor.cancel' AND entity_id='" + visitorId + "'"))
                .isEqualTo("true");
    }

    @Test
    @DisplayName("AC-3: no credential, no revocation signal")
    void noCredentialNoRevocation() throws Exception {
        String visitorId = preRegister("Plain Guest");
        String requestId = scalar("SELECT request_id FROM vms.visitors WHERE id='"
                + visitorId + "'");

        var res = post("/api/v1/pre-registrations/" + visitorId + "/cancel", null, token("recone"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"revocationRequested\":false");
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + requestId
                + "' AND event_type='CredentialRevocationRequested'")).isEqualTo("0");
    }

    // ---- AC-5: another floor's record ----

    @Test
    @DisplayName("AC-5: another floor's pre-registration is 404, byte-identical to a missing id, "
            + "and the attempt is audited")
    void foreignFloorIsNotFound() throws Exception {
        String theirs = preRegister("Floor One Guest");   // created by recone, floor 1
        String neverExisted = UUID.randomUUID().toString();
        String rectwo = token("rectwo");                  // stationed on floor 2

        var foreignAmend = patch(theirs, "{\"fullName\":\"Reaching Over\"}", rectwo);
        var missingAmend = patch(neverExisted, "{\"fullName\":\"Reaching Over\"}", rectwo);
        var foreignCancel = post("/api/v1/pre-registrations/" + theirs + "/cancel", null, rectwo);

        assertThat(foreignAmend.statusCode()).isEqualTo(404);
        assertThat(foreignAmend.body()).isEqualTo(missingAmend.body());
        assertThat(foreignCancel.statusCode()).isEqualTo(404);
        assertThat(scalar("SELECT full_name FROM vms.visitors WHERE id='" + theirs + "'"))
                .isEqualTo("Floor One Guest");

        // The response says nothing, so the trail must: the probe is recorded (AC-5).
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.register.object%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("AC-5's other half: the same record is editable by a colleague on the same floor")
    void sameFloorColleagueMayMaintainIt() throws Exception {
        // Floor-scoped, not creator-scoped: the story's owner is the desk, not the individual.
        String visitorId = preRegister("Shared Desk Guest");

        var res = patch(visitorId, "{\"company\":\"Corrected Ltd\"}", token("recone2"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT company FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("Corrected Ltd");
    }

    @Test
    @DisplayName("a tenant user cannot reach the maintenance endpoints at all")
    void tenantIsForbidden() throws Exception {
        String visitorId = preRegister("Guarded Guest");

        assertThat(patch(visitorId, "{\"fullName\":\"X\"}", token("tenantuser")).statusCode())
                .isEqualTo(403);
        assertThat(post("/api/v1/pre-registrations/" + visitorId + "/cancel", null,
                token("tenantuser")).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("unauthenticated maintenance is 401")
    void unauthenticated() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(patch(id, "{\"fullName\":\"X\"}", null).statusCode()).isEqualTo(401);
        assertThat(post("/api/v1/pre-registrations/" + id + "/cancel", null, null).statusCode())
                .isEqualTo(401);
    }

    // ---- helpers ----

    private String preRegister(String name) throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);
        var res = post("/api/v1/pre-registrations", """
                {"fullName":"%s","email":"guest-%s@example.test","company":"Analytical Ltd",
                 "appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(name, UUID.randomUUID(), from, from.plus(2, ChronoUnit.HOURS)),
                token("recone"));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "visitorId");
    }

    private String tenantSubmitsTwo() throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);
        var res = post("/api/v1/visitor-requests", """
                {"hostId":"%s","scheduledFrom":"%s","scheduledTo":"%s","purpose":"Group visit",
                 "visitors":[{"fullName":"Guest One"},{"fullName":"Guest Two"}]}
                """.formatted(hostOnFloorOne, from, from.plus(2, ChronoUnit.HOURS)),
                token("tenantuser"));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    private String token(String username) throws Exception {
        String password = switch (username) {
            case "tenantuser" -> "tenant-password-1234";
            case "fmadmin" -> "fm-admin-password-123";
            case "rectwo" -> "floor-two-password-12";
            case "recone2" -> "floor-one-b-password1";
            default -> "floor-one-password-12";
        };
        String body = send("POST", "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> patch(String visitorId, String body, String bearer)
            throws Exception {
        return send("PATCH", "/api/v1/pre-registrations/" + visitorId, body, bearer);
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send("POST", path, body, bearer);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send("GET", path, null, bearer);
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

    private static void seed() throws Exception {
        var hasher = new Pbkdf2PasswordHasher();
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String building = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.buildings (id, code, name) VALUES ('" + building
                    + "','B1','Tower')");
            String floorOne = UUID.randomUUID().toString();
            String floorTwo = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.floors (id, building_id, code, name) VALUES ('" + floorOne
                    + "','" + building + "','F1','Floor 1')");
            s.execute("INSERT INTO vms.floors (id, building_id, code, name) VALUES ('" + floorTwo
                    + "','" + building + "','F2','Floor 2')");

            floorOneTenant = UUID.randomUUID();
            UUID floorTwoTenant = UUID.randomUUID();
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('"
                    + floorOneTenant + "','T1','Floor One Tenant','" + floorOne + "')");
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('"
                    + floorTwoTenant + "','T2','Floor Two Tenant','" + floorTwo + "')");

            hostOnFloorOne = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostOnFloorOne + "','" + floorOneTenant + "','Host One', true)");

            String recOne = reception(s, "RC1", floorOne);
            String recTwo = reception(s, "RC2", floorTwo);

            String floorRole = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");

            user(s, hasher, "recone", "floor-one-password-12", floorRole, recOne, null);
            // A second receptionist at the same desk: floor scope, not creator scope.
            user(s, hasher, "recone2", "floor-one-b-password1", floorRole, recOne, null);
            user(s, hasher, "rectwo", "floor-two-password-12", floorRole, recTwo, null);
            user(s, hasher, "tenantuser", "tenant-password-1234", tenantRole, null,
                    floorOneTenant.toString());
            user(s, hasher, "fmadmin", "fm-admin-password-123", fmRole, null, null);
        }
    }

    private static String reception(Statement s, String code, String floorId) throws Exception {
        String id = UUID.randomUUID().toString();
        s.execute("INSERT INTO vms.receptions (id, floor_id, code, name) VALUES ('" + id + "','"
                + floorId + "','" + code + "','Reception " + code + "')");
        return id;
    }

    private static void user(Statement s, Pbkdf2PasswordHasher hasher, String username,
                             String password, String roleId, String receptionId, String tenantId)
            throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " reception_id, tenant_id, is_active) VALUES ('" + UUID.randomUUID() + "','"
                + username + "','" + username + "@ex.com','" + username + "','"
                + hasher.hash(password.toCharArray()) + "','" + roleId + "',"
                + (receptionId == null ? "NULL" : "'" + receptionId + "'") + ","
                + (tenantId == null ? "NULL" : "'" + tenantId + "'") + ", true)");
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
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
