-- =====================================================================
-- V6__activation_tokens.sql — single-use, time-limited account activation.
-- US-02.2.2 · T-02.2.2.3
--
-- A user provisioned by an administrator (single create or bulk import) has
-- no password — activation is out of band (US-02.2.1 AC-1). This table holds
-- the activation tokens the user redeems to set an initial password. Only a
-- SHA-256 hash of the token is stored, never the token; a token activates
-- exactly once and expires at its TTL.
-- =====================================================================
SET search_path TO vms, public;

CREATE TABLE vms.activation_tokens (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid        NOT NULL REFERENCES vms.users(id) ON DELETE CASCADE,
    token_hash   text        NOT NULL UNIQUE,
    expires_at   timestamptz NOT NULL,
    redeemed_at  timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_activation_tokens_user ON vms.activation_tokens(user_id);

COMMENT ON TABLE vms.activation_tokens IS
    'Single-use, hashed, time-limited account-activation tokens (US-02.2.2 T-02.2.2.3).';
