package com.pantropi.vms.application.visitor.port;

import java.util.UUID;

/**
 * Outbound port for publishing domain events (US-07.1.1, AC-2).
 *
 * <p>Implemented as a transactional outbox: the event is written in the same transaction as the
 * aggregate, so it is never lost and never published for a rolled-back write. A Kafka relay drains
 * it when Kafka is available (TDD §3, §8).
 *
 * <p>The payload map must carry identifiers and timing only — <strong>never</strong> a visitor
 * name, email or phone.
 */
public interface DomainEventPublisher {

    void publish(String eventType, String aggregateType, UUID aggregateId, String payloadJson);
}
