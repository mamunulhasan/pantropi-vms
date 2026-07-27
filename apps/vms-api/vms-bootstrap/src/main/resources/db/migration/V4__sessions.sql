-- =====================================================================
-- V4__sessions.sql — server-side session store for revocation & rotation.
-- US-02.1.2 · T-02.1.2.1
--
-- The SRS/TDD call for a Redis-backed session store (TDD §3, §7). Redis is
-- unavailable in this build (no server, client library not cached, Maven
-- 403), so the store is realised here in PostgreSQL behind the SessionStore
-- port. A Redis adapter can replace this later with no change above the port
-- (documented deviation on US-02.1.2).
--
-- One row per session "family". Refresh-token rotation updates
-- refresh_token_hash in place; a presented refresh token whose hash does not
-- match the current one, on a still-active session, is treated as replay and
-- the family is revoked (AC-5). Only a SHA-256 hash of the refresh token is
-- stored, never the token itself.
-- =====================================================================
SET search_path TO vms, public;

CREATE TABLE vms.sessions (
    id                  uuid PRIMARY KEY,
    user_id             uuid        NOT NULL REFERENCES vms.users(id) ON DELETE CASCADE,
    username            text        NOT NULL,
    role_code           text        NOT NULL,
    refresh_token_hash  text        NOT NULL,
    issued_at           timestamptz NOT NULL DEFAULT now(),
    expires_at          timestamptz NOT NULL,
    revoked             boolean     NOT NULL DEFAULT false,
    revoked_reason      text,
    revoked_at          timestamptz
);

CREATE INDEX idx_sessions_user ON vms.sessions(user_id);
CREATE INDEX idx_sessions_active ON vms.sessions(id) WHERE revoked = false;

COMMENT ON TABLE vms.sessions IS
    'Server-side session store for revocation and refresh rotation (US-02.1.2). '
    'PostgreSQL realisation of the TDD Redis session store; swap the adapter to move to Redis.';
