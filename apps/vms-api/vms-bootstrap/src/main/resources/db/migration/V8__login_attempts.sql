-- =====================================================================
-- V8__login_attempts.sql — failed-login counting and account lockout.
-- US-02.3.1 · T-02.3.1.2 — NFR-SEC-01 (SRS B1), OWASP ASVS V2
--
-- T-02.3.1.2 specifies Redis for the attempt counters. Redis is unavailable
-- in this build, so the counters live in PostgreSQL behind the
-- LoginAttemptStore port — the same substitution already accepted for
-- sessions in US-02.1.2. A Redis adapter replaces this without touching the
-- use case.
--
-- Keyed by username rather than user id, deliberately: an attempt against an
-- account that does not exist must be counted and must behave identically to
-- one against an account that does, or the lockout itself becomes a username
-- oracle (AC-5).
--
-- The key is citext, and that is load-bearing rather than tidiness. The use
-- case keys this table by what the client typed, because for an unknown
-- account there is no canonical username to use instead. Under plain text,
-- 'alice', 'Alice' and 'ALICE' would each get their own counter and an
-- attacker would get the threshold as many times over as they cared to vary
-- the capitalisation. citext collapses them, matching vms.users.username,
-- which is citext for the same reason.
--
-- Also adds password rotation tracking to vms.users (T-02.3.1.3).
-- =====================================================================
SET search_path TO vms, public;

CREATE TABLE vms.login_attempts (
    username          citext      PRIMARY KEY,
    failed_count      integer     NOT NULL DEFAULT 0,
    locked_until      timestamptz,
    last_attempt_at   timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_login_attempts_locked
    ON vms.login_attempts(locked_until) WHERE locked_until IS NOT NULL;

COMMENT ON TABLE vms.login_attempts IS
    'Failed-login counters and lockout windows (US-02.3.1). PostgreSQL realisation of the '
    'Redis counter in T-02.3.1.2; keyed by username so unknown accounts behave identically.';

-- Rotation support: when the password was last set, and whether the next login
-- must change it (administrator reset, or rotation interval elapsed).
ALTER TABLE vms.users
    ADD COLUMN password_changed_at   timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN must_change_password  boolean     NOT NULL DEFAULT false;

COMMENT ON COLUMN vms.users.password_changed_at IS
    'When the password was last set — drives the rotation interval (US-02.3.1 T-02.3.1.3).';
COMMENT ON COLUMN vms.users.must_change_password IS
    'Forces a password change before any other endpoint is reachable (US-02.3.1 AC).';

-- The flag is stamped onto the session at login so the authorization gate can
-- enforce it from the session lookup it already performs on every request —
-- no extra query, and no need to put it in the token where it could not be
-- withdrawn before expiry.
ALTER TABLE vms.sessions
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false;

COMMENT ON COLUMN vms.sessions.must_change_password IS
    'Copied from vms.users at login; the authorization gate confines such a session to the '
    'password-change endpoints (US-02.3.1).';
