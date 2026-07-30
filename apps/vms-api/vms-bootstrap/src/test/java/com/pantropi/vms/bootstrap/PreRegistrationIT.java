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
 * INTEGRATION TEST for US-08.1.1 — a floor receptionist pre-registers a visitor (FR-VMS-03, SRS B1).
 *
 * <p>The seeded floor deliberately hosts <strong>two</strong> tenants, because that is the normal
 * shape of a commercial tower and it is the case AC-2's chain cannot resolve on its own. A single
 * -tenant floor is seeded alongside it so both branches are exercised against the real schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class PreRegistrationIT {

    private static EmbeddedPostgres pg;
    private static UUID sharedFloorTenantA;
    private static UUID sharedFloorTenantB;
    private static UUID soleTenant;
    private static UUID otherFloorTenant;
    private static UUID hostOnSharedA;
    private static UUID contractorType;

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

    // ---- AC-1 ----

    @Test
    @DisplayName("AC-1: a request and a visitor are created, pre_scheduled, with the appointment "
            + "window on the visitor row")
    void createsRequestAndVisitorWithWindow() throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        var res = post("""
                {"fullName":"Ada Lovelace","email":"Ada@Example.TEST","phone":"+880 1712-345678",
                 "company":"Analytical Ltd","visitorTypeId":"%s","hostId":"%s","tenantId":"%s",
                 "purpose":"Site inspection","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(contractorType, hostOnSharedA, sharedFloorTenantA, from,
                from.plus(2, ChronoUnit.HOURS)), token("floorrec"));

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.headers().firstValue("Location")).isPresent();
        String requestId = field(res.body(), "requestId");
        String visitorId = field(res.body(), "visitorId");

        assertThat(scalar("SELECT visit_kind::text FROM vms.visitor_requests WHERE id='"
                + requestId + "'")).isEqualTo("pre_scheduled");
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='"
                + requestId + "'")).isEqualTo("submitted");

        // AC-1: the appointment window is on the visitor row, not only on the request.
        assertThat(scalar("SELECT appointment_from FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isNotNull();
        assertThat(scalar("SELECT appointment_to FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isNotNull();
        assertThat(scalar("SELECT status::text FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("pending");
        // Normalised on the way in, as on any other path (US-07.1.2).
        assertThat(scalar("SELECT email FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("ada@example.test");
        assertThat(scalar("SELECT phone FROM vms.visitors WHERE id='" + visitorId + "'"))
                .isEqualTo("+8801712345678");
    }

    @Test
    @DisplayName("a pre-registration joins the FM Admin's approval queue like any other request")
    void preRegistrationReachesTheQueue() throws Exception {
        String requestId = preRegister(sharedFloorTenantA, "Queue Guest");

        assertThat(get("/api/v1/visitor-requests/pending?size=100", token("fmadmin")).body())
                .contains(requestId);
    }

    // ---- AC-2, AC-6: whose visit is it ----

    @Test
    @DisplayName("AC-2: on a floor with one tenant, the tenant is derived and no payload is needed")
    void singleTenantFloorDerivesTheTenant() throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Sole Guest","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(from, from.plus(1, ChronoUnit.HOURS)), token("solerec"));

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(scalar("SELECT tenant_id FROM vms.visitor_requests WHERE id='"
                + field(res.body(), "requestId") + "'")).isEqualTo(soleTenant.toString());
    }

    @Test
    @DisplayName("AC-2/TODO-20: on a shared floor the chain resolves to two tenants, so one must "
            + "be named — 400, and nothing is written")
    void sharedFloorRequiresAChoice() throws Exception {
        String before = scalar("SELECT count(*) FROM vms.visitor_requests");
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Ambiguous Guest","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(from, from.plus(1, ChronoUnit.HOURS)), token("floorrec"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("tenant_required").contains("2 tenants");
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-6: naming a tenant on another floor is 403, nothing is written, and the "
            + "attempt is audited")
    void foreignFloorIsForbidden() throws Exception {
        String before = scalar("SELECT count(*) FROM vms.visitor_requests");
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Wrong Floor","tenantId":"%s","appointmentFrom":"%s",
                 "appointmentTo":"%s"}
                """.formatted(otherFloorTenant, from, from.plus(1, ChronoUnit.HOURS)),
                token("floorrec"));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("foreign_floor");
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isEqualTo(before);
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.register.own_floor%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("AC-2: a tenantId in the payload cannot reach past the floor — the station decides")
    void payloadCannotWidenTheStation() throws Exception {
        // The mass-assignment case: the body names a tenant, and it is only ever accepted as a
        // selection from what the caller's own reception allows.
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var mine = post("""
                {"fullName":"Mine","tenantId":"%s","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(sharedFloorTenantB, from, from.plus(1, ChronoUnit.HOURS)),
                token("floorrec"));
        var theirs = post("""
                {"fullName":"Theirs","tenantId":"%s","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(soleTenant, from, from.plus(1, ChronoUnit.HOURS)), token("floorrec"));

        assertThat(mine.statusCode()).isEqualTo(201);
        assertThat(theirs.statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("AC-2: a receptionist with no reception assigned cannot pre-register at all")
    void unstationedUserIsRefused() throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Nobody","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(from, from.plus(1, ChronoUnit.HOURS)), token("orphanrec"));

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("station_unresolved");
    }

    // ---- AC-3 ----

    @Test
    @DisplayName("AC-3: the audit names the reception point and the visitor, and the event carries "
            + "ids only")
    void auditAndEventCarryIdsOnly() throws Exception {
        String requestId = preRegister(sharedFloorTenantA, "Audited Guest");
        String receptionId = scalar("SELECT reception_id FROM vms.users WHERE username='floorrec'");

        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor.pre_register'")).isNotEqualTo("0");
        assertThat(scalar("SELECT after_state->>'receptionId' FROM vms.audit_logs"
                + " WHERE action='visitor_request.pre_register' AND entity_id='" + requestId + "'"))
                .isEqualTo(receptionId);

        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + requestId
                + "' AND event_type='VisitorPreRegistered'")).isEqualTo("1");
        String payload = scalar("SELECT payload::text FROM vms.domain_events WHERE aggregate_id='"
                + requestId + "' AND event_type='VisitorPreRegistered'");
        assertThat(payload).contains("visitorId").contains("receptionId").contains("appointmentFrom");
        assertThat(payload).doesNotContain("Audited Guest");
    }

    // ---- AC-5 ----

    @Test
    @DisplayName("AC-5: an appointment older than the grace period is 422, and nothing is written")
    void staleAppointmentIsUnprocessable() throws Exception {
        String before = scalar("SELECT count(*) FROM vms.visitor_requests");
        // The seeded default grace is 60 minutes.
        Instant from = Instant.now().minus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Yesterday Guest","tenantId":"%s","appointmentFrom":"%s",
                 "appointmentTo":"%s"}
                """.formatted(sharedFloorTenantA, from, from.plus(1, ChronoUnit.HOURS)),
                token("floorrec"));

        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(res.body()).contains("appointment_in_past");
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-5: an appointment starting a few minutes ago is fine — a receptionist typing "
            + "somebody in as they arrive is the normal case")
    void recentAppointmentIsAccepted() throws Exception {
        Instant from = Instant.now().minus(10, ChronoUnit.MINUTES);

        var res = post("""
                {"fullName":"Just Arrived","tenantId":"%s","appointmentFrom":"%s",
                 "appointmentTo":"%s"}
                """.formatted(sharedFloorTenantA, from, from.plus(2, ChronoUnit.HOURS)),
                token("floorrec"));

        assertThat(res.statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("AC-5: the grace period is configuration, and widening it accepts what was refused")
    void graceIsConfigurable() throws Exception {
        Instant from = Instant.now().minus(3, ChronoUnit.HOURS);
        String body = """
                {"fullName":"Configured Guest","tenantId":"%s","appointmentFrom":"%s",
                 "appointmentTo":"%s"}
                """.formatted(sharedFloorTenantA, from, from.plus(1, ChronoUnit.HOURS));

        assertThat(post(body, token("floorrec")).statusCode()).isEqualTo(422);

        execute("UPDATE vms.system_settings SET value='1440'"
                + " WHERE key='pre_registration.past_grace_minutes'");
        try {
            assertThat(post(body, token("floorrec")).statusCode()).isEqualTo(201);
        } finally {
            execute("UPDATE vms.system_settings SET value='60'"
                    + " WHERE key='pre_registration.past_grace_minutes'");
        }
    }

    @Test
    @DisplayName("AC-5: an inverted window is 400")
    void invertedWindowIsBadRequest() throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Inverted","tenantId":"%s","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(sharedFloorTenantA, from, from.minus(1, ChronoUnit.HOURS)),
                token("floorrec"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("invalid_window");
    }

    @Test
    @DisplayName("a malformed email is 400 naming the field and never echoing the value")
    void malformedEmailIsRefused() throws Exception {
        String offending = "not-an-email-" + UUID.randomUUID();
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Ada","email":"%s","tenantId":"%s","appointmentFrom":"%s",
                 "appointmentTo":"%s"}
                """.formatted(offending, sharedFloorTenantA, from, from.plus(1, ChronoUnit.HOURS)),
                token("floorrec"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("visitor email").doesNotContain(offending);
    }

    // ---- authorization ----

    @Test
    @DisplayName("a tenant user cannot pre-register: 403, and the denial is audited")
    void tenantCannotPreRegister() throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);

        var res = post("""
                {"fullName":"Not Allowed","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(from, from.plus(1, ChronoUnit.HOURS)), token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.register%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("an unauthenticated pre-registration is 401")
    void unauthenticated() throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);
        assertThat(post("""
                {"fullName":"Anon","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(from, from.plus(1, ChronoUnit.HOURS)), null).statusCode())
                .isEqualTo(401);
    }

    // ---- helpers ----

    private String preRegister(UUID tenantId, String name) throws Exception {
        Instant from = Instant.now().plus(3, ChronoUnit.HOURS);
        var res = post("""
                {"fullName":"%s","tenantId":"%s","appointmentFrom":"%s","appointmentTo":"%s"}
                """.formatted(name, tenantId, from, from.plus(1, ChronoUnit.HOURS)),
                token("floorrec"));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "requestId");
    }

    private String token(String username) throws Exception {
        String password = switch (username) {
            case "tenantuser" -> "tenant-password-1234";
            case "fmadmin" -> "fm-admin-password-123";
            case "solerec" -> "sole-reception-pass-12";
            case "orphanrec" -> "orphan-reception-pw-1";
            default -> "floor-reception-pass-1";
        };
        String body = send("POST", "/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> post(String body, String bearer) throws Exception {
        return send("POST", "/api/v1/pre-registrations", body, bearer);
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

            String sharedFloor = UUID.randomUUID().toString();
            String soleFloor = UUID.randomUUID().toString();
            String otherFloor = UUID.randomUUID().toString();
            for (String[] floor : new String[][]{{sharedFloor, "F1"}, {soleFloor, "F2"},
                    {otherFloor, "F3"}}) {
                s.execute("INSERT INTO vms.floors (id, building_id, code, name) VALUES ('"
                        + floor[0] + "','" + building + "','" + floor[1] + "','Floor "
                        + floor[1] + "')");
            }

            // The shared floor is the realistic case AC-2's chain cannot resolve.
            sharedFloorTenantA = tenant(s, "TA", "Tenant A", sharedFloor);
            sharedFloorTenantB = tenant(s, "TB", "Tenant B", sharedFloor);
            soleTenant = tenant(s, "TS", "Sole Tenant", soleFloor);
            otherFloorTenant = tenant(s, "TO", "Other Floor Tenant", otherFloor);

            hostOnSharedA = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostOnSharedA + "','" + sharedFloorTenantA + "','Host A', true)");

            contractorType = UUID.fromString(single(s,
                    "SELECT id FROM vms.visitor_types WHERE code='CONTRACTOR'"));

            String sharedReception = reception(s, "RC1", sharedFloor);
            String soleReception = reception(s, "RC2", soleFloor);

            String floorRole = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");

            user(s, hasher, "floorrec", "floor-reception-pass-1", floorRole, sharedReception, null);
            user(s, hasher, "solerec", "sole-reception-pass-12", floorRole, soleReception, null);
            // A receptionist with the permission but no station: AC-2's unresolvable case.
            user(s, hasher, "orphanrec", "orphan-reception-pw-1", floorRole, null, null);
            user(s, hasher, "tenantuser", "tenant-password-1234", tenantRole, null,
                    sharedFloorTenantA.toString());
            user(s, hasher, "fmadmin", "fm-admin-password-123", fmRole, null, null);
        }
    }

    private static UUID tenant(Statement s, String code, String name, String floorId)
            throws Exception {
        UUID id = UUID.randomUUID();
        s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + id + "','"
                + code + "','" + name + "','" + floorId + "')");
        return id;
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
