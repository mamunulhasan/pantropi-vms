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

    @Bean
    com.pantropi.vms.application.credential.port.CredentialRepository credentialRepository(
            javax.sql.DataSource dataSource) {
        return new JdbcCredentialRepository(
                new org.springframework.jdbc.core.JdbcTemplate(dataSource));
    }

    /**
     * Issuance needs an {@link com.pantropi.vms.application.acs.port.AcsPort}, and there is only
     * one when {@code vms.acs.mode} names it. A profile that configures no ACS gets no issuance
     * bean either — which is the right failure: a building whose passes are not actually reaching
     * an access control system should not start pretending to issue them.
     */
    @Bean
    com.pantropi.vms.application.credential.usecase.IssueCredential issueCredential(
            com.pantropi.vms.application.credential.port.CredentialRepository credentials,
            com.pantropi.vms.application.acs.port.AcsPort acs,
            com.pantropi.vms.application.identity.port.AuditTrail audit) {
        return new com.pantropi.vms.application.credential.usecase.IssueCredential(
                credentials, acs, audit);
    }
}
