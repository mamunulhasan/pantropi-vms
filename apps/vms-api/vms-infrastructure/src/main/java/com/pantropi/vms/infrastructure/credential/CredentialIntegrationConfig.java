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
     * Issuance needs an {@link com.pantropi.vms.application.acs.port.AcsPort}, so it is declared
     * separately and only when one can exist.
     *
     * <p>This was a real defect when the bean lived on the outer class: that class is gated on
     * {@code vms.identity.enabled} alone, so every profile with identity on and no ACS configured
     * failed to start on a missing {@code AcsPort} — which is most of the integration tests. The
     * intent was always that no ACS means no issuance, not that no ACS means no application.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "vms.acs", name = "mode")
    static class IssuanceConfig {

        @Bean
        com.pantropi.vms.application.credential.usecase.IssueCredential issueCredential(
                com.pantropi.vms.application.credential.port.CredentialRepository credentials,
                com.pantropi.vms.application.acs.port.AcsPort acs,
                com.pantropi.vms.application.identity.port.AuditTrail audit,
                com.pantropi.vms.application.notification.usecase.SendCredentialEmail sendEmail) {
            return new com.pantropi.vms.application.credential.usecase.IssueCredential(
                    credentials, acs, audit, sendEmail);
        }

        /** Lets an approval mint the passes (US-09.1.2), without the visitor context knowing how. */
        @Bean
        com.pantropi.vms.application.visitor.port.CredentialIssuance credentialIssuance(
                com.pantropi.vms.application.credential.usecase.IssueCredential issueCredential) {
            return new IssueCredentialBridge(issueCredential);
        }

        /** Reads back the pass for the portal's renderer. No ACS of its own, but the same route. */
        @Bean
        com.pantropi.vms.application.credential.usecase.GetCredentialPass getCredentialPass(
                com.pantropi.vms.application.credential.port.CredentialRepository credentials) {
            return new com.pantropi.vms.application.credential.usecase.GetCredentialPass(credentials);
        }
    }

    /**
     * What approval does when no access control system is configured: nothing, successfully.
     *
     * <p>{@code ApproveVisitorRequest} needs a {@link com.pantropi.vms.application.visitor.port.CredentialIssuance}
     * in every profile, but the real one lives in {@link IssuanceConfig} behind {@code vms.acs.mode}.
     * Without this fallback, every ACS-less profile would fail to start on a missing bean — which is
     * exactly the defect that took down the integration tests when the issuance use case was first
     * wired, and it is worth one bean to not build it a second time.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(
            com.pantropi.vms.application.visitor.port.CredentialIssuance.class)
    com.pantropi.vms.application.visitor.port.CredentialIssuance noCredentialIssuance() {
        return (actor, visitorId, from, to) ->
                com.pantropi.vms.application.visitor.port.CredentialIssuance.Outcome.SKIPPED;
    }
}
