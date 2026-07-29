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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.1.2 — visitor details on a request (FR-VMS-01, SRS B1), over real HTTP
 * against real PostgreSQL.
 *
 * <p>The database is doing real work in two of these: {@code email} is {@code citext}, so the
 * case-insensitivity assertion is about the column rather than about Java, and
 * {@code visitor_type_id} is a real foreign key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class VisitorDetailsIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID hostA;
    private static UUID guestType;
    private static UUID retiredType;

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

    // ---- AC-1, AC-2 ----

    @Test
    @DisplayName("AC-1: name, email, phone, company and visitor type all reach vms.visitors")
    void allFiveFieldsPersist() throws Exception {
        String id = submit("""
                {"fullName":"Ada Lovelace","email":"Ada.Lovelace@Example.TEST",
                 "phone":"+880 1712-345678","company":"Analytical Ltd","visitorTypeId":"%s"}
                """.formatted(guestType));

        assertThat(row(id, "full_name")).isEqualTo("Ada Lovelace");
        assertThat(row(id, "company")).isEqualTo("Analytical Ltd");
        assertThat(row(id, "visitor_type_id")).isEqualTo(guestType.toString());
        // AC-2: normalised on the way in.
        assertThat(row(id, "email")).isEqualTo("ada.lovelace@example.test");
        assertThat(row(id, "phone")).isEqualTo("+8801712345678");
    }

    @Test
    @DisplayName("AC-1: the email column is citext, so a differently-cased address matches — on a "
            + "connection with the default search_path, which is what the application uses")
    void emailIsStoredCaseInsensitively() throws Exception {
        String id = submit("""
                {"fullName":"Alan Turing","email":"Alan.Turing@Example.TEST"}
                """);

        // The comparison is the database's, not Java's. This is the assertion that caught V11's
        // defect: with citext installed in the vms schema, the '=' operator was unresolvable from a
        // default search_path and PostgreSQL fell back to case-sensitive text equality — so this
        // returned 0. It is deliberately run on a plain connection, because that is what the
        // application has.
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE id='" + id
                + "' AND email = 'ALAN.TURING@EXAMPLE.TEST'")).isEqualTo("1");
        assertThat(scalar("SHOW search_path")).doesNotContain("vms");
    }

    @Test
    @DisplayName("the visitor type is optional — a request without one is still valid")
    void visitorTypeIsOptional() throws Exception {
        String id = submit("{\"fullName\":\"Grace Hopper\"}");

        assertThat(row(id, "visitor_type_id")).isNull();
        assertThat(row(id, "status")).isEqualTo("pending");
    }

    // ---- AC-3 ----

    @Test
    @DisplayName("AC-3: an inactive visitor type is a 400 naming the field, and nothing is written")
    void inactiveVisitorTypeRefused() throws Exception {
        String before = scalar("SELECT count(*) FROM vms.visitor_requests");

        String name = "Retired Type Guest";
        var res = submitRaw("""
                {"fullName":"%s","visitorTypeId":"%s"}
                """.formatted(name, retiredType));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("visitorTypeId");
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE full_name='" + name + "'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("AC-3: an unknown visitor type is refused identically — no partial visitor row")
    void unknownVisitorTypeRefused() throws Exception {
        var unknown = submitRaw("""
                {"fullName":"Nobody","visitorTypeId":"%s"}
                """.formatted(UUID.randomUUID()));
        var inactive = submitRaw("""
                {"fullName":"Nobody","visitorTypeId":"%s"}
                """.formatted(retiredType));

        assertThat(unknown.statusCode()).isEqualTo(400);
        // Same body: confirming which of the two it was would tell a caller that some id they
        // guessed corresponds to a real type.
        assertThat(unknown.body()).isEqualTo(inactive.body());
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE full_name='Nobody'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("AC-3: one bad type in a group refuses the whole request, including the good ones")
    void oneBadTypeRefusesTheGroup() throws Exception {
        String before = scalar("SELECT count(*) FROM vms.visitor_requests");

        var res = submitRaw("""
                {"fullName":"Good Guest","visitorTypeId":"%s"},
                {"fullName":"Bad Guest","visitorTypeId":"%s"}
                """.formatted(guestType, retiredType));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isEqualTo(before);
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE full_name='Good Guest'"))
                .isEqualTo("0");
    }

    // ---- AC-4 ----

    @Test
    @DisplayName("AC-4: a malformed email is 400 naming the field, and the value is in no log line")
    void malformedEmailIsRejectedAndNotLogged() throws Exception {
        String offending = "definitely-not-an-email-" + UUID.randomUUID();
        CapturingHandler captured = attachToApplicationLogger();
        try {
            var res = submitRaw("""
                    {"fullName":"Ada Lovelace","email":"%s"}
                    """.formatted(offending));

            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("visitor email");
            // The response names the field; it must not echo what was typed.
            assertThat(res.body()).doesNotContain(offending);
            assertThat(captured.text()).doesNotContain(offending);
        } finally {
            captured.detach();
        }
    }

    @Test
    @DisplayName("AC-4: an over-long name is 400 naming the field without echoing it")
    void oversizedNameRejected() throws Exception {
        String huge = "N".repeat(201);

        var res = submitRaw("{\"fullName\":\"" + huge + "\"}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("visitor name").doesNotContain(huge);
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE full_name LIKE 'NNN%'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("AC-4: a malformed phone number is refused the same way")
    void malformedPhoneRejected() throws Exception {
        var res = submitRaw("{\"fullName\":\"Ada\",\"phone\":\"0800-CALL-NOW\"}");

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("visitor phone").doesNotContain("CALL-NOW");
    }

    // ---- helpers ----

    private String submit(String visitorJson) throws Exception {
        var res = submitRaw(visitorJson);
        assertThat(res.statusCode()).isEqualTo(201);
        String requestId = field(res.body(), "id");
        return scalar("SELECT id FROM vms.visitors WHERE request_id='" + requestId
                + "' ORDER BY created_at LIMIT 1");
    }

    private HttpResponse<String> submitRaw(String visitorsJson) throws Exception {
        Instant from = Instant.now().plus(30, ChronoUnit.DAYS);
        return post("/api/v1/visitor-requests",
                "{\"hostId\":\"" + hostA + "\",\"scheduledFrom\":\"" + from + "\","
                        + "\"scheduledTo\":\"" + from.plus(2, ChronoUnit.HOURS) + "\","
                        + "\"purpose\":\"Quarterly review\",\"visitors\":[" + visitorsJson + "]}",
                token());
    }

    private String token() throws Exception {
        String body = post("/api/v1/auth/login",
                "{\"username\":\"tenantuser\",\"password\":\"tenant-password-1234\"}", null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static String row(String visitorId, String column) throws Exception {
        return scalar("SELECT " + column + "::text FROM vms.visitors WHERE id='" + visitorId + "'");
    }

    /**
     * Captures {@code com.pantropi.vms} logging only.
     *
     * <p>Scoped rather than attached to the root logger on purpose: Tomcat's {@code Http11InputBuffer}
     * logs raw request bytes at {@code FINER}, so a root capture would fail this assertion on
     * container behaviour rather than on ours. That is documented as a deployment note in the local
     * runbook.
     */
    private static CapturingHandler attachToApplicationLogger() {
        Logger logger = Logger.getLogger("com.pantropi.vms");
        CapturingHandler handler = new CapturingHandler(logger);
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        return handler;
    }

    private static final class CapturingHandler extends Handler {
        private final StringBuilder text = new StringBuilder();
        private final Logger attachedTo;

        CapturingHandler(Logger attachedTo) {
            this.attachedTo = attachedTo;
        }

        @Override public synchronized void publish(LogRecord record) {
            text.append(record.getMessage()).append('\n');
            if (record.getParameters() != null) {
                for (Object p : record.getParameters()) {
                    text.append(p).append('\n');
                }
            }
            if (record.getThrown() != null) {
                text.append(record.getThrown()).append('\n');
            }
        }

        @Override public void flush() { }

        @Override public void close() { }

        synchronized String text() { return text.toString(); }

        void detach() { attachedTo.removeHandler(this); }
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

            // V2 seeds GUEST/CONTRACTOR/VIP/INTERVIEW; take one and retire another.
            guestType = UUID.fromString(single(s,
                    "SELECT id FROM vms.visitor_types WHERE code='GUEST'"));
            retiredType = UUID.fromString(single(s,
                    "SELECT id FROM vms.visitor_types WHERE code='INTERVIEW'"));
            s.execute("UPDATE vms.visitor_types SET is_active=false WHERE id='" + retiredType + "'");

            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash,"
                    + " role_id, tenant_id, is_active) VALUES ('" + UUID.randomUUID()
                    + "','tenantuser','t@ex.com','Tina Tenant','"
                    + hasher.hash("tenant-password-1234".toCharArray()) + "','" + tenantRole
                    + "','" + tenantA + "', true)");
        }
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
