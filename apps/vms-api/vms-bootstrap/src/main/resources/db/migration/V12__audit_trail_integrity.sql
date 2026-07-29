-- US-07.4.3 — the decision trail (FR-VMS-02, SRS B1; FR-AUD-01 via TDD §4.6).
--
-- Two things the story asks for that no amount of application code can provide.
--
-- =====================================================================
-- 1. Append-only, at the database and not by convention (AC-3)
-- =====================================================================
-- An audit trail whose rows can be edited is a record of what someone was willing to leave behind,
-- which is a different and much weaker claim than a record of what happened. Every write path in
-- this application only inserts — but "the code only inserts" is a property of today's code, and the
-- point of an audit trail is to be trustworthy after the code has changed.
--
-- Enforced with a trigger AND a revoke, because neither alone is sufficient:
--
--   * REVOKE is the grant-level control the AC names, and it is what stops an ordinary application
--     role from issuing an UPDATE at all. It does nothing to a superuser, and the local development
--     datasource connects as one.
--   * The trigger applies to everyone including superusers, and it fails loudly. A RULE ... DO
--     INSTEAD NOTHING was the alternative and is worse: it makes an UPDATE silently succeed while
--     changing nothing, so a tampering attempt looks like it worked.
--
-- Deliberately not blocked: INSERT, and TRUNCATE is left to the table owner. A retention policy will
-- eventually need to remove aged rows, and that is a deliberate administrative act rather than an
-- application write — when it arrives it will need its own migration to say so.

CREATE OR REPLACE FUNCTION vms.audit_logs_are_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'vms.audit_logs is append-only: % on audit row is not permitted (US-07.4.3 AC-3)',
        TG_OP
        USING ERRCODE = 'restrict_violation';
END
$$;

COMMENT ON FUNCTION vms.audit_logs_are_append_only() IS
    'Refuses UPDATE and DELETE on the audit trail (US-07.4.3 AC-3). Applies to superusers too, '
    'which a REVOKE does not.';

DROP TRIGGER IF EXISTS trg_audit_logs_append_only ON vms.audit_logs;
CREATE TRIGGER trg_audit_logs_append_only
    BEFORE UPDATE OR DELETE ON vms.audit_logs
    FOR EACH ROW EXECUTE FUNCTION vms.audit_logs_are_append_only();

REVOKE UPDATE, DELETE ON vms.audit_logs FROM PUBLIC;

-- =====================================================================
-- 2. A permission for reading the trail (AC-6)
-- =====================================================================
-- Reading the decision trail needs an authority, and none of the existing ones is it.
--
--   * report.view was the tempting choice and is wrong. V9 leaves it granted to nobody pending
--     TODO-16, precisely because no role description mentions reporting. Granting it here — for a
--     different purpose — would answer that open question as a side effect of an unrelated story.
--   * user.manage would work today because SYSTEM_ADMIN holds it, and would mean "whoever can
--     manage users can read the audit trail". That is the kind of drift that makes a permission
--     model stop describing anything.
--
-- So: a new, specific permission, granted to SYSTEM_ADMIN — the role the story is written for
-- ("As a System Administrator I want every approval decision recorded immutably"). It is granted to
-- nobody else, including the FM Admins who take the decisions: reading back who decided what is an
-- oversight function, not part of taking a decision.

INSERT INTO vms.permissions (code, description)
VALUES ('audit.view', 'Read the immutable audit trail for an entity (FR-AUD-01, US-07.4.3)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO vms.role_permissions (role_id, permission_id)
SELECT r.id, p.id
  FROM vms.roles r
  CROSS JOIN vms.permissions p
 WHERE r.code = 'SYSTEM_ADMIN'
   AND p.code = 'audit.view'
ON CONFLICT DO NOTHING;

-- The trail is read by entity, so that is the access path to support.
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity
    ON vms.audit_logs (entity_type, entity_id, created_at DESC);
