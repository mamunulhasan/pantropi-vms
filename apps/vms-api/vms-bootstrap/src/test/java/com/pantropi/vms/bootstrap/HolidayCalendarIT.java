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
 * INTEGRATION TEST for US-04.7.1 — the holiday calendar over real HTTP against real PostgreSQL.
 *
 * <p>Each test uses its own year, so the shared database cannot make them order-dependent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class HolidayCalendarIT {

    private static final String PASSWORD = "correct horse battery staple";
    private static EmbeddedPostgres pg;
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
    @DisplayName("AC-1: an entry is written and audited")
    void addIsAudited() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/holidays",
                entry("2030-03-20", "Eid al-Fitr", false), token());

        assertThat(res.statusCode()).isEqualTo(201);
        String id = extract(res.body(), "id");
        assertThat(scalar("SELECT name FROM vms.holiday_calendar WHERE id='" + id + "'"))
                .isEqualTo("Eid al-Fitr");
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='holiday.created' AND entity_id='" + id + "'"))
                .contains("2030-03-20");
    }

    @Test
    @DisplayName("AC-2: a working exception is distinguishable from a non-working holiday")
    void workingExceptionIsVisible() throws Exception {
        String admin = token();
        post("/api/v1/admin/holidays", entry("2031-03-20", "Eid", false), admin);
        post("/api/v1/admin/holidays", entry("2031-03-21", "Compensating Saturday", true), admin);

        String body = get("/api/v1/admin/holidays?year=2031", admin).body();

        assertThat(body).contains("\"date\":\"2031-03-20\"", "\"working\":false");
        assertThat(body).contains("\"date\":\"2031-03-21\"", "\"working\":true");
    }

    @Test
    @DisplayName("AC-2: year and range queries return the right sets, ends included")
    void yearAndRangeQueries() throws Exception {
        String admin = token();
        post("/api/v1/admin/holidays", entry("2032-01-01", "New Year", false), admin);
        post("/api/v1/admin/holidays", entry("2032-06-15", "Mid year", false), admin);
        post("/api/v1/admin/holidays", entry("2032-12-31", "Year end", false), admin);

        // A year covers both ends of the year.
        String year = get("/api/v1/admin/holidays?year=2032", admin).body();
        assertThat(year).contains("2032-01-01", "2032-06-15", "2032-12-31");

        // A range is inclusive of both ends too.
        String range = get("/api/v1/admin/holidays?from=2032-06-15&to=2032-12-31", admin).body();
        assertThat(range).contains("2032-06-15", "2032-12-31").doesNotContain("2032-01-01");
    }

    @Test
    @DisplayName("AC-4: removal deletes the row, and the audit keeps the prior state")
    void removeDeletesAndAudits() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/holidays",
                entry("2033-05-01", "Labour Day", false), admin).body(), "id");

        assertThat(delete("/api/v1/admin/holidays/" + id, admin).statusCode()).isEqualTo(204);

        // The row really is gone — this is the one master data table where that is correct.
        assertThat(scalar("SELECT count(*) FROM vms.holiday_calendar WHERE id='" + id + "'"))
                .isEqualTo("0");
        // ...and the audit is now the only record it ever existed.
        assertThat(scalar("SELECT before_state::text FROM vms.audit_logs "
                + "WHERE action='holiday.deleted' AND entity_id='" + id + "'"))
                .contains("2033-05-01").contains("Labour Day");
    }

    @Test
    @DisplayName("AC-5: a duplicate date is 409")
    void duplicateDateRefused() throws Exception {
        String admin = token();
        post("/api/v1/admin/holidays", entry("2034-01-01", "New Year", false), admin);

        HttpResponse<String> res = post("/api/v1/admin/holidays",
                entry("2034-01-01", "New Year again", false), admin);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(scalar("SELECT count(*) FROM vms.holiday_calendar "
                + "WHERE holiday_date = DATE '2034-01-01'")).isEqualTo("1");
    }

    @Test
    @DisplayName("AC-3: a clean bulk import applies in full and reports every row")
    void cleanImportApplies() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/holidays/import",
                "[" + entry("2035-01-01", "New Year", false) + ","
                        + entry("2035-03-20", "Eid", false) + ","
                        + entry("2035-12-25", "Christmas", false) + "]", token());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"accepted\":true", "\"applied\":3");
        assertThat(scalar("SELECT count(*) FROM vms.holiday_calendar "
                + "WHERE holiday_date BETWEEN DATE '2035-01-01' AND DATE '2035-12-31'"))
                .isEqualTo("3");
    }

    @Test
    @DisplayName("AC-6: one clash leaves the calendar untouched, and names the offending row")
    void importIsAllOrNothing() throws Exception {
        String admin = token();
        post("/api/v1/admin/holidays", entry("2036-01-01", "New Year", false), admin);

        HttpResponse<String> res = post("/api/v1/admin/holidays/import",
                "[" + entry("2036-01-01", "Clash", false) + ","
                        + entry("2036-03-20", "Eid", false) + ","
                        + entry("2036-12-25", "Christmas", false) + "]", admin);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"accepted\":false", "\"applied\":0");
        assertThat(res.body()).contains("already exists");

        // Nothing from the import landed — a half-imported year is worse than a rejected one,
        // because nobody can tell by looking which half arrived.
        assertThat(scalar("SELECT count(*) FROM vms.holiday_calendar "
                + "WHERE holiday_date BETWEEN DATE '2036-01-02' AND DATE '2036-12-31'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("AC-6: a duplicate within the submitted set is caught before anything is written")
    void importRejectsInternalDuplicates() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/holidays/import",
                "[" + entry("2037-03-20", "Eid", false) + ","
                        + entry("2037-03-20", "Eid again", false) + "]", token());

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("more than once");
        assertThat(scalar("SELECT count(*) FROM vms.holiday_calendar "
                + "WHERE holiday_date = DATE '2037-03-20'")).isEqualTo("0");
    }

    @Test
    @DisplayName("negative: masterdata.view alone can read but not add or remove")
    void viewerCannotWrite() throws Exception {
        String admin = token();
        String viewer = token("viewer");
        String id = extract(post("/api/v1/admin/holidays",
                entry("2038-01-01", "New Year", false), admin).body(), "id");

        assertThat(get("/api/v1/admin/holidays?year=2038", viewer).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/admin/holidays", entry("2038-05-01", "Labour", false), viewer)
                .statusCode()).isEqualTo(403);
        assertThat(delete("/api/v1/admin/holidays/" + id, viewer).statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.holiday_calendar WHERE id='" + id + "'"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("negative: a blank name is 400 and an unknown id is 404")
    void validationAndNotFound() throws Exception {
        String admin = token();

        assertThat(post("/api/v1/admin/holidays", entry("2039-01-01", "  ", false), admin)
                .statusCode()).isEqualTo(400);
        assertThat(delete("/api/v1/admin/holidays/" + UUID.randomUUID(), admin).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/v1/admin/holidays?year=2039", null).statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

    private static String entry(String date, String name, boolean working) {
        return "{\"date\":\"" + date + "\",\"name\":\"" + name + "\",\"working\":" + working + "}";
    }

    private String token() throws Exception {
        return token("mdadmin");
    }

    private String token(String username) throws Exception {
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}", null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), bearer);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).GET(), bearer);
    }

    private HttpResponse<String> delete(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).DELETE(), bearer);
    }

    private HttpResponse<String> send(HttpRequest.Builder b, String bearer) throws Exception {
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://localhost:" + port; }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static void seed() throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String sysRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");
            insertUser(s, "mdadmin", hash, sysRole);
            insertUser(s, "viewer", hash, fmRole);
            grant(s, "SYSTEM_ADMIN", "masterdata.view");
            grant(s, "SYSTEM_ADMIN", "masterdata.edit");
            grant(s, "FM_ADMIN", "masterdata.view");
        }
    }

    private static void grant(Statement s, String role, String permission) throws Exception {
        s.execute("""
                INSERT INTO vms.role_permissions (role_id, permission_id)
                SELECT r.id, p.id FROM vms.roles r JOIN vms.permissions p ON p.code = '%s'
                WHERE r.code = '%s' ON CONFLICT DO NOTHING"""
                .formatted(permission, role));
    }

    private static void insertUser(Statement s, String u, String hash, String roleId)
            throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " is_active) VALUES ('" + UUID.randomUUID() + "', '" + u + "', '" + u
                + "@ex.com', '" + u + "', '" + hash + "', '" + roleId + "', true)");
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
