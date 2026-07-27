package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Transactional-outbox adapter for {@link DomainEventPublisher} (US-07.1.1, AC-2).
 *
 * <p>Writes the event to {@code vms.domain_events} inside the caller's transaction, so it commits
 * or rolls back with the aggregate — an event is never lost, and never published for a write that
 * failed. A relay drains {@code published_at IS NULL} to Kafka when Kafka is available, which is why
 * the use case needs no change to move onto the bus (TDD §3, §8).
 */
public final class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final JdbcTemplate jdbc;

    public OutboxDomainEventPublisher(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void publish(String eventType, String aggregateType, UUID aggregateId, String payloadJson) {
        jdbc.update("""
                INSERT INTO vms.domain_events (event_type, aggregate_type, aggregate_id, payload)
                VALUES (?, ?, ?, ?::jsonb)
                """, eventType, aggregateType, aggregateId, payloadJson);
    }
}
