-- =====================================================================
-- V9__role_permission_grants.sql — the role-to-permission matrix.
-- US-03.1.1 · T-03.1.1.2 — FR-USR-02 (TDD-derived), authorised by ADR-0004
--
-- Completes the incremental lift of discrepancy D-15. V3 granted MASTER_ADMIN
-- its two FR-ADM-01 (SRS B1) authorities, V5 gave SYSTEM_ADMIN user.manage and
-- V7 gave TENANT visitor.request, each as the single grant its story needed.
-- This one states the whole matrix, so that from here on a role holds what its
-- description says it holds rather than whatever the last story happened to
-- require.
--
-- Every grant below is justified against the role description seeded in V2.
-- The rule from T-03.1.1.2 is "grant no permission that the role's description
-- does not support", and the justification column is how that stays checkable
-- by someone reading this later rather than trusting it was true once.
--
-- Idempotent: the composite primary key on role_permissions makes each insert
-- a no-op on re-run, and the four grants from V3/V5/V7 are simply re-asserted.
--
-- DELIBERATELY NOT GRANTED
--
--   credential.override  to nobody, pending TODO-04. MASTER_ADMIN's description
--                        ("approves access, issues credentials, overrides")
--                        does support it — this is the one place the matrix
--                        contradicts a description on purpose. D-12 records
--                        that manual override is an unbacked capability with no
--                        SRS requirement defining when it is legitimate, and a
--                        permission whose rules nobody has written is not one
--                        to hand out. It is granted when TODO-04 closes.
--
--   report.view          to nobody, pending TODO-16. No role description
--   report.export        mentions reporting, and D-12 records export as
--                        unbacked. Granting on the strength of the permission
--                        row existing would be inventing a requirement.
-- =====================================================================
SET search_path TO vms, public;

INSERT INTO vms.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM (VALUES
    -- role                  permission            justification from the V2 role description
    ('TENANT',             'visitor.request'),  -- "Submits visitor requests"
    ('FLOOR_RECEPTIONIST', 'visitor.register'), -- "Pre-registers visitors from each floor"
    ('FLOOR_RECEPTIONIST', 'masterdata.view'),  -- ...and must read floors and tenants to do it
    ('FM_ADMIN',           'visitor.approve'),  -- "Reviews and approves visitor requests"
    ('MASTER_ADMIN',       'visitor.approve'),  -- "approves access" (FR-ADM-01, SRS B1)
    ('MASTER_ADMIN',       'credential.issue'), -- "issues credentials" (FR-ADM-01, SRS B1)
    ('SYSTEM_ADMIN',       'user.manage'),      -- "Manages ... users"
    ('SYSTEM_ADMIN',       'masterdata.edit'),  -- "Manages configuration"
    ('SYSTEM_ADMIN',       'settings.manage'),  -- "Manages configuration ... integration"
    -- masterdata.view for SYSTEM_ADMIN is an addition to the matrix as written
    -- in T-03.1.1.2, and deliberate. Without it the role can create a building
    -- but cannot list buildings: every read route is guarded by
    -- masterdata.view and every write by masterdata.edit. An administrator who
    -- can write what they cannot read is not a coherent role, and no operator
    -- would accept it. Editing implies viewing; the reverse does not.
    ('SYSTEM_ADMIN',       'masterdata.view')
) AS grant_matrix(role_code, permission_code)
JOIN vms.roles r       ON r.code = grant_matrix.role_code
JOIN vms.permissions p ON p.code = grant_matrix.permission_code
ON CONFLICT (role_id, permission_id) DO NOTHING;

COMMENT ON TABLE vms.role_permissions IS
    'Role-to-permission grants (FR-USR-02). The authoritative matrix is V9; '
    'credential.override, report.view and report.export are ungranted pending '
    'TODO-04 and TODO-16.';
