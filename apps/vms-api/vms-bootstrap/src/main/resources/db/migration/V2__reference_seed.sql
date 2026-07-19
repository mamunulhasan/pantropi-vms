-- =====================================================================
-- V2__reference_seed.sql — reference data from schema §14, made idempotent.
-- US-01.5.1 · T-01.5.1.3
--
-- The published DDL's seed INSERTs carry no conflict handling; here every
-- insert is keyed on its natural unique column so a manual re-run is a
-- no-op (AC-3).
--
-- DELIBERATELY ABSENT:
--   * role_permissions grants — the published schema seeds none (a defect:
--     with deny-by-default RBAC this is a total lockout, discrepancy D-15).
--     The grant matrix is authorization policy and needs client sign-off;
--     it arrives with EPIC-03, never invented here.
--   * any user row or password hash — the bootstrap SYSTEM_ADMIN account is
--     created interactively in US-02.4.1, never seeded (T-01.5.1.3).
-- =====================================================================
SET search_path TO vms, public;

INSERT INTO vms.roles (code, name, description) VALUES
    ('MASTER_ADMIN',       'Master Admin / Central Receptionist', 'Central reception; approves access, issues credentials, overrides'),
    ('FLOOR_RECEPTIONIST', 'Floor Receptionist',                  'Pre-registers visitors from each floor'),
    ('FM_ADMIN',           'Facility Management Admin',           'Reviews and approves visitor requests'),
    ('TENANT',             'Tenant',                              'Submits visitor requests'),
    ('SYSTEM_ADMIN',       'System Administrator',                'Manages configuration, users, integration')
ON CONFLICT (code) DO NOTHING;

INSERT INTO vms.permissions (code, description) VALUES
    ('masterdata.view',    'View master data'),
    ('masterdata.edit',    'Create/update master data'),
    ('user.manage',        'Manage users, roles, permissions'),
    ('visitor.request',    'Submit visitor requests'),
    ('visitor.approve',    'Approve/reject visitor requests'),
    ('visitor.register',   'Pre-register visitors'),
    ('credential.issue',   'Request credential issuance from ACS'),
    ('credential.override','Perform manual credential override'),  -- unbacked capability: D-12/TODO-04; row kept to match the published schema, gated at grant time
    ('report.view',        'View reports and analytics'),
    ('report.export',      'Export reports to Excel/PDF'),         -- unbacked capability: D-12/TODO-16; same treatment
    ('settings.manage',    'Manage system settings')
ON CONFLICT (code) DO NOTHING;

INSERT INTO vms.visitor_types (code, name, description) VALUES
    ('GUEST',      'Guest',       'General appointed visitor'),
    ('CONTRACTOR', 'Contractor',  'Service/maintenance contractor'),
    ('VIP',        'VIP',         'Priority visitor'),
    ('INTERVIEW',  'Interviewee', 'Candidate attending an interview')
ON CONFLICT (code) DO NOTHING;

INSERT INTO vms.pass_types (code, name, default_credential, default_restriction, default_valid_hours) VALUES
    ('DAY_QR',    'Single-day QR pass',    'qr',   'time_bound', 12),
    ('ONE_TIME',  'One-time entry pass',   'qr',   'one_time',   4),
    ('CARD_DAY',  'Single-day RFID card',  'rfid', 'time_bound', 12)
ON CONFLICT (code) DO NOTHING;

INSERT INTO vms.system_settings (key, value, description) VALUES
    ('default_pass_valid_hours',      '12',    'Fallback validity window when a pass type is not specified'),
    ('notification.email.enabled',    'true',  'Master switch for email notifications'),
    ('notification.whatsapp.enabled', 'false', 'WhatsApp disabled until Business API approval (TODO-05)'),
    ('acs.retry.max_attempts',        '5',     'Max outbound ACS retry attempts before dead-letter')
ON CONFLICT (key) DO NOTHING;
