package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.visitor.usecase.ApproveVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.RejectVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Wires the visitor ports to their adapters (US-07.1.1, T-01.2.1.3 convention).
 *
 * <p>Gated on the same {@code vms.identity.enabled} switch as the identity wiring, because a
 * visitor request cannot be submitted without an authenticated tenant, and both need a DataSource.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class VisitorConfig {

    @Bean
    VisitorRequestRepository visitorRequestRepository(DataSource dataSource,
            com.pantropi.vms.application.identity.usecase.ScopePolicy scope) {
        return new JdbcVisitorRequestRepository(new JdbcTemplate(dataSource), scope);
    }

    @Bean
    TenantDirectory tenantDirectory(DataSource dataSource) {
        return new JdbcTenantDirectory(new JdbcTemplate(dataSource));
    }

    @Bean
    DomainEventPublisher domainEventPublisher(DataSource dataSource) {
        return new OutboxDomainEventPublisher(new JdbcTemplate(dataSource));
    }

    @Bean
    SubmitVisitorRequest submitVisitorRequest(VisitorRequestRepository requests,
                                              TenantDirectory tenants, DomainEventPublisher events,
                                              AuditTrail audit, TransactionRunner tx) {
        return new SubmitVisitorRequest(requests, tenants, events, audit, tx);
    }

    /** US-07.4.1 — the decision half of the approval loop. */
    @Bean
    ApproveVisitorRequest approveVisitorRequest(VisitorRequestRepository requests,
                                                DomainEventPublisher events, AuditTrail audit,
                                                TransactionRunner tx, ClockPort clock) {
        return new ApproveVisitorRequest(requests, events, audit, tx, clock);
    }

    /** US-07.4.2 — the other half of the decision, with the reason as an aggregate invariant. */
    @Bean
    RejectVisitorRequest rejectVisitorRequest(VisitorRequestRepository requests,
                                              DomainEventPublisher events, AuditTrail audit,
                                              TransactionRunner tx, ClockPort clock) {
        return new RejectVisitorRequest(requests, events, audit, tx, clock);
    }
}
