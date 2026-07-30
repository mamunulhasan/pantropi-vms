package com.pantropi.vms.infrastructure.credential;

import com.pantropi.vms.application.visitor.port.IssuedCredentials;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * The credential context's answers to other contexts (US-08.1.3).
 *
 * <p>Gated on the identity switch like the visitor wiring that consumes it — a deployment with the
 * visitor API off has nobody to ask this question.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class CredentialIntegrationConfig {

    @Bean
    IssuedCredentials issuedCredentials(DataSource dataSource) {
        return new JdbcIssuedCredentials(new JdbcTemplate(dataSource));
    }
}
