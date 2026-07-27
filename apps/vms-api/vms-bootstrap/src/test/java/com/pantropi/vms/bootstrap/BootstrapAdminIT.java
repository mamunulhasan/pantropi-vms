package com.pantropi.vms.bootstrap;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * END-TO-END INTEGRATION TEST for US-02.4.1 (T-02.4.1.3, AC-5) — and a full-chain proof.
 *
 * <p>Migrates the real schema (V1/V2/V3) on embedded PostgreSQL, boots the app with the
 * first-run bootstrap enabled, and asserts:
 * <ul>
 *   <li>the bootstrap runner created exactly one {@code SYSTEM_ADMIN} on the empty database;</li>
 *   <li>that account can immediately <b>log in</b> (US-02.1.1) — proving migrations → bootstrap →
 *       authentication end to end;</li>
 *   <li>V3 granted {@code MASTER_ADMIN} its two FR-ADM-01 authorities (AC-1).</li>
 * </ul>
 *
 * <p>The schema is migrated inside {@link #datasource} (which runs before the context refresh, and
 * therefore before the bootstrap runner) so the runner sees the table it must populate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.bootstrap.admin.enabled=true",
                "vms.bootstrap.admin.username=sysadmin",
                "vms.bootstrap.admin.password=bootstrap-strong-pw-123",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"   // migrated manually below, before the context
        })
class BootstrapAdminIT {

    private static EmbeddedPostgres pg;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
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
        @Bean
        DataSource dataSource() {
            return pg.getPostgresDatabase();
        }
    }

    @Test
    @DisplayName("AC-5: bootstrap created exactly one SYSTEM_ADMIN and it can log in")
    void bootstrappedAdminCanLogIn() throws Exception {
        assertThat(scalar("SELECT count(*) FROM vms.users")).isEqualTo("1");
        assertThat(scalar("""
                SELECT r.code FROM vms.users u JOIN vms.roles r ON r.id = u.role_id
                WHERE u.username = 'sysadmin'""")).isEqualTo("SYSTEM_ADMIN");

        HttpRequest login = HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"sysadmin\",\"password\":\"bootstrap-strong-pw-123\"}"))
                .build();
        HttpResponse<String> res = client.send(login, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"tokenType\":\"Bearer\"");
    }

    @Test
    @DisplayName("AC-1: V3 granted MASTER_ADMIN its two FR-ADM-01 authorities")
    void masterAdminHasAuthorities() throws Exception {
        assertThat(scalar("""
                SELECT count(*) FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id AND r.code = 'MASTER_ADMIN'
                JOIN vms.permissions p ON p.id = rp.permission_id
                WHERE p.code IN ('visitor.approve','credential.issue')""")).isEqualTo("2");
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
