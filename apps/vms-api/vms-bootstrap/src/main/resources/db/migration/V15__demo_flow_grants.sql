-- =====================================================================
-- V15__demo_flow_grants.sql — two grants so the approval flow is walkable.
--
-- WHAT THIS IS: a deliberate widening of the role-to-permission matrix so
-- that FM_ADMIN can raise a visitor request as well as decide one, and can
-- read back the pass that approving it minted.
--
--   * FM_ADMIN -> visitor.request. V2 describes the role as "Reviews and
--     approves visitor requests"; reviewing is not raising, and an approver
--     who can also submit is an approver approving their own work. This is
--     a demo convenience, requested by the owner, and worth revisiting
--     before any real deployment: segregation of duties between raising and
--     approving is the usual reason the two permissions are separate.
--
--   * FM_ADMIN -> credential.issue. This one is about *reading*, not
--     minting. As of US-09.1.2 approval issues the passes server-side, and
--     no permission is consulted for that — the approval is the authority.
--     But CredentialController carries @RequiresPermission("credential.issue")
--     at class level, and the FM has to fetch the pass to show it. The grant
--     buys visibility of something they already caused.
--
-- WHAT THIS IS NOT: a requirements decision. V9 remains the matrix the role
-- descriptions justify.
--
-- FLOOR_RECEPTIONIST deliberately gets nothing here. An earlier draft gave
-- the desk credential.issue so a receptionist could mint a pass by hand;
-- that is gone with the button that used it. It was the wider of the two
-- grants by far — it let a desk mint a working pass for a visitor whose
-- request was still `submitted`, which is approval bypassed rather than
-- approval delegated.
--
-- Idempotent, like V9: ON CONFLICT DO NOTHING, so a re-run is a no-op and
-- a database that already has these rows is left alone.
-- =====================================================================
SET search_path TO vms, public;

INSERT INTO vms.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    -- role          permission          why it is being added
    ('FM_ADMIN',   'visitor.request'),  -- raise a request as well as decide one
    ('FM_ADMIN',   'credential.issue')  -- read back the pass that approving it minted
) AS demo_grants(role_code, permission_code)
JOIN vms.roles r       ON r.code = demo_grants.role_code
JOIN vms.permissions p ON p.code = demo_grants.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

