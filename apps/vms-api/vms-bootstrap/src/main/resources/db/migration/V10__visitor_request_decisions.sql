-- US-07.4.1 (T-07.4.1.1/T-07.4.1.2) and US-07.4.2 (T-07.4.2.1) — FR-VMS-02 (SRS B1).
--
-- The baseline records *that* a request was decided (status, approved_by) but not *why* or *when*.
-- US-07.4.1 AC-4 asks for an optional note stored with an approval and visible to the requesting
-- tenant; US-07.4.2 makes the same field mandatory for a rejection. One column serves both: it is
-- the reason for the decision, whichever way the decision went, so a reader never has to join two
-- nullable columns to find out why a request ended where it did.
--
-- No optimistic-locking version column is added, deliberately. US-07.4.1 AC-6 requires that two
-- simultaneous approvals produce one success and one 409; the decision is a compare-and-set on the
-- status itself (UPDATE ... WHERE status = 'submitted'), which is optimistic concurrency control
-- with the state as its own version token. A separate integer would be a second thing to keep
-- consistent with the state it is guarding, and stale-version conflicts that did not correspond to
-- an actual competing decision would surface as 409s the FM Admin could not explain.

ALTER TABLE vms.visitor_requests
    ADD COLUMN IF NOT EXISTS decision_reason text,
    ADD COLUMN IF NOT EXISTS decided_at      timestamptz;

COMMENT ON COLUMN vms.visitor_requests.decision_reason IS
    'Why the request was approved or rejected; optional on approval, mandatory on rejection '
    '(US-07.4.1 AC-4, US-07.4.2 AC-2). Operator-authored text — must not contain visitor PII.';

COMMENT ON COLUMN vms.visitor_requests.decided_at IS
    'When the approve/reject/cancel decision was taken, from the application clock. Distinct from '
    'updated_at, which any later write moves.';

-- A reason is only meaningful once a decision has been taken. Enforced here as well as in the
-- aggregate so a direct SQL write cannot leave a submitted request carrying an explanation for a
-- decision nobody made.
ALTER TABLE vms.visitor_requests
    ADD CONSTRAINT visitor_requests_reason_needs_decision
        CHECK (decision_reason IS NULL OR status <> 'submitted');

-- The approval queue and the tenant's own list both read by status; the decision endpoints read by
-- id, which is already the primary key.
CREATE INDEX IF NOT EXISTS idx_visitor_requests_status_created
    ON vms.visitor_requests (status, created_at DESC);
