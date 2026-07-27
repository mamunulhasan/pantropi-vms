-- =====================================================================
-- V7__visitor_request_submission.sql — enable tenant visitor-request submission.
-- US-07.1.1 · FR-VMS-01 (SRS B1)
--
-- Two things this story needs that the baseline does not yet provide:
--
-- 1. The TENANT role must hold visitor.request, or the endpoint FR-VMS-01
--    describes is unreachable by the only role meant to use it. The permission
--    itself is already seeded (V2); this grants it. Continues the incremental,
--    justified lift of D-15 — V3 gave MASTER_ADMIN its FR-ADM-01 authorities,
--    V5 gave SYSTEM_ADMIN user.manage. The full matrix still awaits sign-off.
--
-- 2. A durable domain-event outbox. The TDD publishes domain events to Kafka
--    (§3, §8); Kafka is unavailable in this build, so events are written to
--    this table inside the same transaction as the aggregate. That preserves
--    the property that actually matters — an event is never lost and never
--    published for a rolled-back write — and a Kafka relay can drain it later
--    without any change to the use case.
-- =====================================================================
SET search_path TO vms, public;

INSERT INTO vms.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM vms.roles r
JOIN vms.permissions p ON p.code = 'visitor.request'
WHERE r.code = 'TENANT'
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE TABLE vms.domain_events (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type     text        NOT NULL,
    aggregate_type text        NOT NULL,
    aggregate_id   uuid        NOT NULL,
    payload        jsonb       NOT NULL,
    occurred_at    timestamptz NOT NULL DEFAULT now(),
    published_at   timestamptz
);

CREATE INDEX idx_domain_events_unpublished
    ON vms.domain_events(occurred_at) WHERE published_at IS NULL;

COMMENT ON TABLE vms.domain_events IS
    'Transactional outbox for domain events (US-07.1.1). Payloads carry identifiers and '
    'timing only - never visitor personal data. Drained to Kafka when it is available.';
