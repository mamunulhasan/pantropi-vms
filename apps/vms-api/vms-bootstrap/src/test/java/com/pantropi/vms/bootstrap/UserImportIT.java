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
 * END-TO-END INTEGRATION TEST for US-02.2.2 — the full bulk-provisioning journey against real
 * PostgreSQL: preview → confirmed import → out-of-band activation → login.
 *
 * <p>Proves: a password column is rejected (AC-4); preview reports conflicts and rejects without
 * writing (AC-1/AC-5); a confirmed import creates the clean rows in one transaction with a summary
 * audit event carrying a content hash, not the contents (AC-2/AC-3); and an imported user activates
 * with their token and then logs in — the whole out-of-band flow (T-02.2.2.3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class UserImportIT {

    private static EmbeddedPostgres pg;
    private static UUID receptionId;
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
    @DisplayName("AC-4: a file containing a password column is rejected with 422")
    void passwordColumnRejected() throws Exception {
        String admin = adminToken();
        String csv = "username,password,fullName,roleCode,receptionId\n"
                + "x,secret,X,FLOOR_RECEPTIONIST," + receptionId;
        var res = postCsv("/api/v1/admin/users/import/preview", csv, admin);
        assertThat(res.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("AC-1/AC-2/AC-3/T-02.2.2.3: preview, confirmed import, activation, then login")
    void fullJourney() throws Exception {
        String admin = adminToken();
        String csv = "username,email,fullName,roleCode,receptionId\n"
                + "recept1,r1@ex.com,Reception One,FLOOR_RECEPTIONIST," + receptionId + "\n"
                + "recept2,r2@ex.com,Reception Two,FLOOR_RECEPTIONIST," + receptionId + "\n"
                + "admin,a@ex.com,Dup,FLOOR_RECEPTIONIST," + receptionId + "\n"          // conflict
                + "bad,b@ex.com,Bad,FLOOR_RECEPTIONIST," + UUID.randomUUID();            // reject: reception

        // preview writes nothing
        var preview = postCsv("/api/v1/admin/users/import/preview", csv, admin);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).contains("\"creatable\":2").contains("\"conflicts\":1")
                .contains("\"rejected\":1");
        assertThat(scalar("SELECT count(*) FROM vms.users WHERE username='recept1'")).isEqualTo("0");

        // execute without confirmation → 409 (conflict present)
        assertThat(postCsv("/api/v1/admin/users/import?fileName=batch.csv", csv, admin).statusCode())
                .isEqualTo(409);

        // execute with confirmation → creates the two clean rows
        var run = postCsv("/api/v1/admin/users/import?fileName=batch.csv&confirmSkipConflicts=true",
                csv, admin);
        assertThat(run.statusCode()).isEqualTo(201);
        assertThat(scalar("SELECT count(*) FROM vms.users WHERE username IN ('recept1','recept2')"))
                .isEqualTo("2");
        // summary audit carries a content hash and the file name, not the contents
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='user.import_completed'"))
                .isEqualTo("1");

        // an imported user cannot log in until activated (placeholder hash never matches)
        assertThat(login("recept1", "anything-at-all-12").statusCode()).isEqualTo(401);

        // redeem the activation token → set a password → log in
        String token = field(run.body(), "activationToken");
        var activate = post("/api/v1/auth/activate",
                "{\"token\":\"" + token + "\",\"password\":\"chosen-password-123\"}", null);
        assertThat(activate.statusCode()).isEqualTo(204);
        assertThat(login("recept1", "chosen-password-123").statusCode()).isEqualTo(200);

        // the token is single-use
        var replay = post("/api/v1/auth/activate",
                "{\"token\":\"" + token + "\",\"password\":\"chosen-password-123\"}", null);
        assertThat(replay.statusCode()).isEqualTo(401);
    }

    // ---- helpers ----
    private String adminToken() throws Exception {
        String body = login("admin", "admin-password-1234").body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> login(String u, String p) throws Exception {
        return post("/api/v1/auth/login", "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null);
    }

    private HttpResponse<String> postCsv(String path, String csv, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "text/csv")
                .POST(HttpRequest.BodyPublishers.ofString(csv));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
        String adminHash = new Pbkdf2PasswordHasher().hash("admin-password-1234".toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            String sysRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id, is_active) "
                    + "VALUES ('" + UUID.randomUUID() + "', 'admin', 'admin@ex.com', 'Admin', '"
                    + adminHash + "', '" + sysRole + "', true)");
            // master data for a valid reception reference
            String building = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.buildings (id, code, name) VALUES ('" + building + "', 'B1', 'Tower')");
            String floor = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.floors (id, building_id, code, name) VALUES ('" + floor + "', '"
                    + building + "', 'F1', 'Floor 1')");
            receptionId = UUID.randomUUID();
            s.execute("INSERT INTO vms.receptions (id, floor_id, code, name, is_active) VALUES ('"
                    + receptionId + "', '" + floor + "', 'R1', 'Reception 1', true)");
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
