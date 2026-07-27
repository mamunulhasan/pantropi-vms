package com.pantropi.vms.bootstrap;

import com.pantropi.vms.interfaces.rest.security.PublicRoutes;
import com.pantropi.vms.interfaces.rest.security.RequiresAuthentication;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ROUTE AUTHORIZATION COVERAGE (US-03.2.1, T-03.2.1.3, AC-3).
 *
 * <p>Enumerates every route the framework has mapped and asserts each one either declares a
 * permission ({@link RequiresPermission}), declares that authentication alone suffices
 * ({@link RequiresAuthentication}), or appears in the reviewed {@link PublicRoutes} allowlist.
 *
 * <p>This is the test that keeps deny-by-default true over time: adding an endpoint without a
 * declaration fails the build naming the offending route and method, rather than quietly shipping a
 * public endpoint.
 */
@SpringBootTest(properties = {
        "vms.identity.enabled=true",
        "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
        "spring.flyway.enabled=false"
})
class RouteAuthorizationCoverageIT {

    private static EmbeddedPostgres pg;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
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
    @DisplayName("AC-3: every mapped route is permission-guarded, authentication-guarded or allowlisted")
    void everyRouteIsGuardedOrAllowlisted() {
        List<String> undeclared = new ArrayList<>();

        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            if (declares(handler)) {
                continue;
            }
            for (String pattern : patternsOf(entry.getKey())) {
                if (PublicRoutes.isPublic(pattern)) {
                    continue;
                }
                undeclared.add(methodsOf(entry.getKey()) + " " + pattern
                        + "  ->  " + handler.getBeanType().getSimpleName()
                        + "#" + handler.getMethod().getName());
            }
        }

        assertThat(undeclared)
                .as("Every route must declare @RequiresPermission or @RequiresAuthentication, or be "
                        + "listed in PublicRoutes. Undeclared routes are denied at runtime and are a "
                        + "security defect — add a declaration, or add the path to the reviewed "
                        + "allowlist with a reason.")
                .isEmpty();
    }

    @Test
    @DisplayName("the allowlist stays short and contains only deliberately public paths")
    void allowlistIsMinimal() {
        // A guard on the guard: if this fails, someone widened the unauthenticated surface.
        assertThat(PublicRoutes.EXACT).containsExactlyInAnyOrder(
                "/api/v1/auth/login",
                "/api/v1/auth/refresh",
                "/api/v1/auth/activate",
                "/error");
        assertThat(PublicRoutes.PREFIXES).containsExactly("/actuator/health");
    }

    private static boolean declares(HandlerMethod handler) {
        return handler.getMethodAnnotation(RequiresPermission.class) != null
                || handler.getMethodAnnotation(RequiresAuthentication.class) != null
                || handler.getBeanType().getAnnotation(RequiresPermission.class) != null
                || handler.getBeanType().getAnnotation(RequiresAuthentication.class) != null;
    }

    @SuppressWarnings("deprecation")   // patterns condition remains the portable accessor here
    private static Set<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues();
        }
        PatternsRequestCondition patterns = info.getPatternsCondition();
        return patterns == null ? Set.of() : patterns.getPatterns();
    }

    private static String methodsOf(RequestMappingInfo info) {
        var methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() ? "ANY" : methods.toString();
    }
}
