package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.DecisionTrail;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.application.visitor.port.VisitorTypeDirectory;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.visitor.usecase.AmendVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.ApproveVisitorRequest;
import com.pantropi.vms.application.visitor.usecase.CancelVisitorRequest;
import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;
import com.pantropi.vms.application.visitor.port.VisitorRequestQueries;
import com.pantropi.vms.application.visitor.usecase.MyVisitorRequests;
import com.pantropi.vms.application.visitor.port.ReceptionScope;
import com.pantropi.vms.application.visitor.port.RegistrationPolicy;
import com.pantropi.vms.application.visitor.usecase.PendingApprovals;
import com.pantropi.vms.application.visitor.usecase.PreRegisterVisitor;
import com.pantropi.vms.application.visitor.usecase.RequestHistory;
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

    /** US-07.3.1 — the queue read model, scoped through the same policy as every other read. */
    @Bean
    ApprovalQueueStore approvalQueueStore(DataSource dataSource,
            com.pantropi.vms.application.identity.usecase.ScopePolicy scope) {
        return new JdbcApprovalQueueStore(new JdbcTemplate(dataSource), scope);
    }

    @Bean
    PendingApprovals pendingApprovals(ApprovalQueueStore queue) {
        return new PendingApprovals(queue);
    }

    /** US-07.6.1 — the tenant's own list and detail, scoped by the same policy. */
    @Bean
    VisitorRequestQueries visitorRequestQueries(DataSource dataSource,
            com.pantropi.vms.application.identity.usecase.ScopePolicy scope) {
        return new JdbcVisitorRequestQueries(new JdbcTemplate(dataSource), scope);
    }

    @Bean
    MyVisitorRequests myVisitorRequests(VisitorRequestQueries queries, AuditTrail audit) {
        return new MyVisitorRequests(queries, audit);
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
                                              AuditTrail audit, TransactionRunner tx,
                                              VisitorTypeDirectory visitorTypes) {
        return new SubmitVisitorRequest(requests, tenants, events, audit, tx, visitorTypes);
    }

    /** US-07.4.1 — the decision half of the approval loop. */
    @Bean
    ApproveVisitorRequest approveVisitorRequest(VisitorRequestRepository requests,
                                                DomainEventPublisher events, AuditTrail audit,
                                                TransactionRunner tx, ClockPort clock) {
        return new ApproveVisitorRequest(requests, events, audit, tx, clock);
    }

    /** US-07.4.3 — the decision trail, read under audit.view rather than a tenant predicate. */
    @Bean
    DecisionTrail decisionTrail(DataSource dataSource) {
        return new JdbcDecisionTrail(new JdbcTemplate(dataSource));
    }

    @Bean
    RequestHistory requestHistory(DecisionTrail trail) {
        return new RequestHistory(trail);
    }

    /** US-08.1.1 — the second input path: a floor receptionist registers a visitor at the desk. */
    @Bean
    PreRegisterVisitor preRegisterVisitor(VisitorRequestRepository requests,
                                          ReceptionScope receptions, TenantDirectory tenants,
                                          VisitorTypeDirectory visitorTypes,
                                          RegistrationPolicy policy, DomainEventPublisher events,
                                          AuditTrail audit, TransactionRunner tx, ClockPort clock) {
        return new PreRegisterVisitor(requests, receptions, tenants, visitorTypes, policy, events,
                audit, tx, clock);
    }

    /** US-07.1.3 — the tenant's own control over a request it raised. */
    @Bean
    CancelVisitorRequest cancelVisitorRequest(VisitorRequestRepository requests,
                                              DomainEventPublisher events, AuditTrail audit,
                                              TransactionRunner tx, ClockPort clock) {
        return new CancelVisitorRequest(requests, events, audit, tx, clock);
    }

    @Bean
    AmendVisitorRequest amendVisitorRequest(VisitorRequestRepository requests,
                                            TenantDirectory tenants,
                                            VisitorTypeDirectory visitorTypes,
                                            DomainEventPublisher events, AuditTrail audit,
                                            TransactionRunner tx, ClockPort clock) {
        return new AmendVisitorRequest(requests, tenants, visitorTypes, events, audit, tx, clock);
    }

    /** US-07.4.2 — the other half of the decision, with the reason as an aggregate invariant. */
    @Bean
    RejectVisitorRequest rejectVisitorRequest(VisitorRequestRepository requests,
                                              DomainEventPublisher events, AuditTrail audit,
                                              TransactionRunner tx, ClockPort clock) {
        return new RejectVisitorRequest(requests, events, audit, tx, clock);
    }
}
