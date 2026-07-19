package com.pantropi.vms.bootstrap;

import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.infrastructure.shared.SystemClockAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TESTS for US-01.2.1 AC-4 and T-01.2.1.3.
 *
 * <p>Boots the real Spring context under the {@code test} profile with no database
 * configured — exactly the "starts against an empty database" condition of AC-4, taken at
 * its current strongest form (no datasource exists until F-01.5 lands).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VmsApplicationIT {

    @Autowired TestRestTemplate http;
    @Autowired ClockPort clockPort;
    // Two beans of this type exist (MVC + actuator); we want MVC's business-endpoint map.
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    @DisplayName("AC-4: context loads and /actuator/health returns UP")
    void healthIsUp() {
        ResponseEntity<String> r = http.getForEntity("/actuator/health", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("AC-4: no business endpoint is registered")
    void noBusinessEndpoints() {
        // Spring Boot registers its own /error controller; anything NOT from a framework
        // package would be a business controller that has no story behind it.
        var business = mappings.getHandlerMethods().values().stream()
                .filter(h -> !h.getBeanType().getPackageName().startsWith("org.springframework"))
                .toList();
        assertThat(business)
                .as("no @RequestMapping business endpoints may exist yet, found: %s", business)
                .isEmpty();

        ResponseEntity<String> r = http.getForEntity("/api/v1/anything", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("T-01.2.1.3: the reference port resolves to its infrastructure adapter")
    void clockPortResolvesToAdapter() {
        assertThat(clockPort).isInstanceOf(SystemClockAdapter.class);
        assertThat(clockPort.now()).isCloseTo(Instant.now(),
                org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS));
    }
}
