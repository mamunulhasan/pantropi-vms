-- =====================================================================
-- V3__master_admin_authority.sql — grant MASTER_ADMIN its FR-ADM-01 authority.
-- US-02.4.1 · T-02.4.1.1 (AC-1)
--
-- FR-ADM-01 (SRS B1): "one Master Admin at central reception with authority to
-- approve visitor access and initiate credential requests." Those two
-- authorities map to the seeded permissions visitor.approve and
-- credential.issue, so granting them to MASTER_ADMIN is SRS-justified, not
-- invented.
--
-- This is a PARTIAL lift of discrepancy D-15 (the schema seeds no
-- role_permissions at all): it grants only the two permissions FR-ADM-01
-- names. The full role->permission matrix for every role still requires
-- client sign-off (EPIC-03) and is not invented here.
--
-- Idempotent: the composite primary key on role_permissions makes the insert
-- a no-op on re-run.
-- =====================================================================
SET search_path TO vms, public;

INSERT INTO vms.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM vms.roles r
JOIN vms.permissions p ON p.code IN ('visitor.approve', 'credential.issue')
WHERE r.code = 'MASTER_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
