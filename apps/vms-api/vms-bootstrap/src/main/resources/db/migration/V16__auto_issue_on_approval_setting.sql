-- US-09.1.2 (AC-2) — whether approving a visitor request also issues each visitor's pass.
--
-- Every key in SettingsCatalogue has a row here, for the reason V14 gives: the catalogue supplies a
-- fallback in code, but a key with no row is invisible to GET /api/v1/admin/settings, so an
-- administrator cannot see it, cannot change it, and has no way to discover that it governs
-- anything.
--
-- true, because the story exists to remove a step: "a visitor approved this morning has their pass
-- before they arrive, and nobody has to remember to press a button". A building that wants a person
-- between an approval and a working pass sets this false and gets US-09.1.1 manual issuance back —
-- which is precisely why AC-2 insists the trigger is a policy and not a hard-wired behaviour.
--
-- Seeding it explicitly also settles the backlog's warning about "failing open to automatic
-- issuance": with the row always present, the code-side default is a safety net rather than the
-- normal path, and turning issuance off is a visible, audited change to a setting somebody can find.

INSERT INTO vms.system_settings (key, value, description) VALUES
    ('credential.auto_issue_on_approval', 'true',
     'Whether approving a visitor request also issues each visitor''s pass (US-09.1.2 AC-2)')
ON CONFLICT (key) DO NOTHING;
