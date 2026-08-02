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

        /** Reads back the pass for the portal's renderer. No ACS of its own, but the same route. */
        @Bean
        com.pantropi.vms.application.credential.usecase.GetCredentialPass getCredentialPass(
                com.pantropi.vms.application.credential.port.CredentialRepository credentials) {
            return new com.pantropi.vms.application.credential.usecase.GetCredentialPass(credentials);
        }
    }

    /**
     * How an approval mints passes — or declines to, when no access control system is configured.
     *
     * <p>One bean, chosen at wiring time from whether {@code IssueCredential} exists, rather than
     * two beans and a condition. The earlier shape had the real bridge inside {@link IssuanceConfig}
     * and a {@code @ConditionalOnMissingBean} fallback out here, and that is a trap:
     * {@code @ConditionalOnMissingBean} is only dependable inside auto-configuration, where Spring
     * controls the order. In a user {@code @Configuration} it is evaluated in definition order, so
     * whenever the fallback was processed before {@code IssuanceConfig} registered the real one,
     * <em>both</em> were registered and every injection point saw two candidates. It passed locally
     * and failed on CI, which is exactly the failure mode a condition-free bean removes.
     *
     * <p>{@link ObjectProvider} is what makes that possible: it resolves lazily, so asking for a
     * bean that the ACS gate never created is an empty answer rather than a startup failure.
     */
    @Bean
    com.pantropi.vms.application.visitor.port.CredentialIssuance credentialIssuance(
            org.springframework.beans.factory.ObjectProvider<
                    com.pantropi.vms.application.credential.usecase.IssueCredential> issuance) {

        com.pantropi.vms.application.credential.usecase.IssueCredential useCase =
                issuance.getIfAvailable();

        // No ACS means no issuance, not no application: the approval still stands, and the
        // per-visitor outcome says plainly that nothing was minted.
        return useCase == null
                ? (actor, visitorId, from, to) ->
                        com.pantropi.vms.application.visitor.port.CredentialIssuance.Outcome.SKIPPED
                : new IssueCredentialBridge(useCase);
    }
}
