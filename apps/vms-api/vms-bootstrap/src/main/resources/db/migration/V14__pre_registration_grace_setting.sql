-- US-08.1.1 (AC-5) — how far into the past a pre-registered appointment may start.
--
-- Every key in SettingsCatalogue has a row here, and that is deliberate rather than incidental: the
-- catalogue supplies a fallback in code, but a key with no row is invisible to
-- GET /api/v1/admin/settings, so an administrator cannot see it, cannot change it, and has no way to
-- discover that it governs anything. A setting nobody can find is a constant with extra steps.
--
-- 60 minutes, because the case this rule exists for is a mistyped date, not a late entry. A
-- receptionist typing a visitor in as they walk up to the desk is the normal workflow — 120 floor
-- reception users doing exactly that is what NFR-SCL-01 sizes the system for — so an appointment that
-- began a few minutes ago is a correct record of what happened. An hour is generous enough to cover
-- a distracted desk and short enough that yesterday's date is still caught.
--
-- Operational, not architectural: a lobby that pre-registers a day ahead and one that types people in
-- on arrival want different numbers, which is why this is a setting and not a constant.

INSERT INTO vms.system_settings (key, value, description) VALUES
    ('pre_registration.past_grace_minutes', '60',
     'How far into the past a pre-registered appointment may start before it is treated as a '
     'mistyped date (US-08.1.1 AC-5)')
ON CONFLICT (key) DO NOTHING;
