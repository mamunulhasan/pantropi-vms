-- =====================================================================
-- V5__system_admin_user_management.sql — grant SYSTEM_ADMIN user.manage.
-- US-02.2.1 · T-02.2.1.2 (AC-6)
--
-- The seeded SYSTEM_ADMIN role is described as "Manages configuration, users,
-- integration" (V2). User administration (US-02.2.1) is guarded by the
-- user.manage permission, so SYSTEM_ADMIN must hold it or the admin API is
-- inaccessible to the only role meant to use it.
--
-- Continues the incremental, justified lift of D-15 (the schema seeds no
-- role_permissions): V3 gave MASTER_ADMIN its two FR-ADM-01 authorities; this
-- grants SYSTEM_ADMIN the single permission this story needs. The full grant
-- matrix for every role still awaits client sign-off (EPIC-03).
--
-- Idempotent via the composite primary key.
-- =====================================================================
SET search_path TO vms, public;

INSERT INTO vms.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM vms.roles r
JOIN vms.permissions p ON p.code = 'user.manage'
WHERE r.code = 'SYSTEM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
