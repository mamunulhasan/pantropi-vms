-- =====================================================================
-- V1__baseline.sql — Project Pinnacle VMS schema baseline
-- US-01.5.1 · T-01.5.1.2
-- Source of truth: docs/vms_schema_postgresql.sql (verbatim; seed data
-- moved to V2__reference_seed.sql). Forward-only; never edit after apply.
-- =====================================================================

-- =====================================================================
-- Project Pinnacle - Visitor Management System (VMS)
-- Database schema - PostgreSQL 14+
--
-- Prepared by: Pantropi Limited
-- Basis:       VMS SRS v2 (functional requirements referenced inline)
-- Notes:       Framework-agnostic DDL. Works whether the application layer
--              is Next.js (Prisma / Drizzle / node-postgres), Spring Boot,
--              or any other client. ACS-owned data (physical credential
--              state, raw gate decisions) is NOT the system of record here;
--              VMS stores references and ACS-reported events only.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 0. Extensions & schema
-- ---------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS pgcrypto;      -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS citext;        -- case-insensitive email/usernames

CREATE SCHEMA IF NOT EXISTS vms;
SET search_path TO vms, public;

-- ---------------------------------------------------------------------
-- 1. Enumerated types
-- ---------------------------------------------------------------------
CREATE TYPE vms.request_status    AS ENUM ('submitted', 'approved', 'rejected', 'cancelled');
CREATE TYPE vms.visitor_status    AS ENUM ('pending', 'approved', 'checked_in', 'inside', 'checked_out', 'expired', 'cancelled', 'no_show');
CREATE TYPE vms.visit_kind        AS ENUM ('pre_scheduled', 'walk_in');
CREATE TYPE vms.credential_type   AS ENUM ('qr', 'rfid');
CREATE TYPE vms.credential_state  AS ENUM ('requested', 'active', 'expired', 'revoked', 'cancelled', 'failed');
CREATE TYPE vms.restriction_type  AS ENUM ('time_bound', 'one_time');
CREATE TYPE vms.access_direction  AS ENUM ('entry', 'exit');
CREATE TYPE vms.notify_channel    AS ENUM ('email', 'whatsapp', 'in_app');
CREATE TYPE vms.notify_type       AS ENUM ('confirmation', 'host', 'reminder', 'exit', 'system_alert');
CREATE TYPE vms.delivery_status   AS ENUM ('queued', 'sent', 'delivered', 'failed');
CREATE TYPE vms.acs_op            AS ENUM ('create_credential', 'deactivate_credential', 'query_status', 'manual_override');
CREATE TYPE vms.acs_req_status    AS ENUM ('pending', 'sent', 'succeeded', 'failed', 'dead_letter');

-- ---------------------------------------------------------------------
-- 2. Shared trigger: maintain updated_at
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION vms.set_updated_at()
RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- =====================================================================
-- 3. MASTER DATA  (FR-CFG-02 .. FR-CFG-08)
-- =====================================================================

CREATE TABLE vms.buildings (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text        NOT NULL UNIQUE,
    name         text        NOT NULL,
    address      text,
    is_active    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.buildings IS 'Master data: buildings (FR-CFG-02)';

CREATE TABLE vms.floors (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    building_id  uuid        NOT NULL REFERENCES vms.buildings(id) ON DELETE RESTRICT,
    code         text        NOT NULL,
    name         text        NOT NULL,
    level_no     integer,
    is_active    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (building_id, code)
);
COMMENT ON TABLE vms.floors IS 'Master data: floors (FR-CFG-03)';

CREATE TABLE vms.tenants (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text        NOT NULL UNIQUE,
    name         text        NOT NULL,
    floor_id     uuid        REFERENCES vms.floors(id) ON DELETE SET NULL,
    contact_email citext,
    contact_phone text,
    is_active    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.tenants IS 'Master data: tenants (FR-CFG-04)';

CREATE TABLE vms.receptions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    floor_id     uuid        NOT NULL REFERENCES vms.floors(id) ON DELETE RESTRICT,
    code         text        NOT NULL,
    name         text        NOT NULL,
    is_central   boolean     NOT NULL DEFAULT false,
    is_active    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (floor_id, code)
);
COMMENT ON TABLE vms.receptions IS 'Master data: reception points (FR-CFG-05)';

CREATE TABLE vms.visitor_types (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text        NOT NULL UNIQUE,
    name         text        NOT NULL,
    description  text,
    is_active    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.visitor_types IS 'Master data: visitor types (FR-CFG-06)';

CREATE TABLE vms.pass_types (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code                text        NOT NULL UNIQUE,
    name                text        NOT NULL,
    default_credential  vms.credential_type  NOT NULL DEFAULT 'qr',
    default_restriction vms.restriction_type NOT NULL DEFAULT 'time_bound',
    default_valid_hours integer     NOT NULL DEFAULT 12 CHECK (default_valid_hours > 0),
    is_active           boolean     NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.pass_types IS 'Master data: pass types (FR-CFG-07)';

CREATE TABLE vms.holiday_calendar (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    holiday_date date        NOT NULL UNIQUE,
    name         text        NOT NULL,
    is_working   boolean     NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.holiday_calendar IS 'Master data: holiday calendar (FR-CFG-08)';

-- =====================================================================
-- 4. USERS, ROLES & PERMISSIONS  (FR-USR-01/02, FR-AUTH-01/02)
-- =====================================================================

CREATE TABLE vms.roles (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text        NOT NULL UNIQUE,
    name         text        NOT NULL,
    description  text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.roles IS 'RBAC roles (FR-USR-02)';

CREATE TABLE vms.permissions (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code         text        NOT NULL UNIQUE,   -- e.g. 'visitor.approve', 'masterdata.edit'
    description  text,
    created_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.permissions IS 'RBAC permissions (FR-USR-02)';

CREATE TABLE vms.role_permissions (
    role_id       uuid NOT NULL REFERENCES vms.roles(id)       ON DELETE CASCADE,
    permission_id uuid NOT NULL REFERENCES vms.permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);
COMMENT ON TABLE vms.role_permissions IS 'RBAC role-permission mapping (FR-USR-02)';

CREATE TABLE vms.users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username      citext      NOT NULL UNIQUE,
    email         citext      UNIQUE,
    full_name     text        NOT NULL,
    password_hash text        NOT NULL,        -- store a hash only, never plaintext
    role_id       uuid        NOT NULL REFERENCES vms.roles(id) ON DELETE RESTRICT,
    reception_id  uuid        REFERENCES vms.receptions(id) ON DELETE SET NULL,
    tenant_id     uuid        REFERENCES vms.tenants(id)    ON DELETE SET NULL,
    is_active     boolean     NOT NULL DEFAULT true,
    last_login_at timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.users IS 'Application users across all roles (FR-USR-01/03/04)';

-- =====================================================================
-- 5. HOSTS & VISITOR WORKFLOW  (FR-VMS-01 .. FR-VMS-06)
-- =====================================================================

CREATE TABLE vms.hosts (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    uuid        NOT NULL REFERENCES vms.tenants(id) ON DELETE CASCADE,
    full_name    text        NOT NULL,
    email        citext,
    phone        text,
    is_active    boolean     NOT NULL DEFAULT true,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.hosts IS 'Tenant-side hosts who receive visitors (FR-VMS-06)';

CREATE TABLE vms.visitor_requests (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         uuid        NOT NULL REFERENCES vms.tenants(id) ON DELETE RESTRICT,
    host_id           uuid        REFERENCES vms.hosts(id)  ON DELETE SET NULL,
    requested_by      uuid        REFERENCES vms.users(id)  ON DELETE SET NULL,
    approved_by       uuid        REFERENCES vms.users(id)  ON DELETE SET NULL,
    visit_kind        vms.visit_kind   NOT NULL DEFAULT 'pre_scheduled',
    scheduled_from    timestamptz,
    scheduled_to      timestamptz,
    status            vms.request_status NOT NULL DEFAULT 'submitted',
    purpose           text,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (scheduled_to IS NULL OR scheduled_from IS NULL OR scheduled_to > scheduled_from)
);
COMMENT ON TABLE vms.visitor_requests IS 'Visitor entry requests & approval (FR-VMS-01/02/05)';

CREATE TABLE vms.visitors (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id        uuid        NOT NULL REFERENCES vms.visitor_requests(id) ON DELETE CASCADE,
    visitor_type_id   uuid        REFERENCES vms.visitor_types(id) ON DELETE SET NULL,
    full_name         text        NOT NULL,
    email             citext,
    phone             text,
    company           text,
    id_document_ref   text,       -- optional reference; avoid storing raw ID images here
    appointment_from  timestamptz,
    appointment_to    timestamptz,
    status            vms.visitor_status NOT NULL DEFAULT 'pending',
    checked_in_at     timestamptz,
    checked_out_at    timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.visitors IS 'Individual visitor records & lifecycle status (FR-VMS-03, FR-ENT-10)';

-- =====================================================================
-- 6. PASSES / CREDENTIALS  (FR-VMS-07 .. FR-VMS-16, FR-ENT-04/05)
-- =====================================================================

CREATE TABLE vms.credentials (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visitor_id        uuid        NOT NULL REFERENCES vms.visitors(id) ON DELETE CASCADE,
    pass_type_id      uuid        REFERENCES vms.pass_types(id) ON DELETE SET NULL,
    credential_type   vms.credential_type  NOT NULL,
    restriction       vms.restriction_type NOT NULL DEFAULT 'time_bound',
    acs_credential_id text,       -- reference returned by ACS; null until ACS confirms
    qr_payload        text,       -- opaque QR content when credential_type = 'qr'
    valid_from        timestamptz NOT NULL,
    valid_to          timestamptz NOT NULL,
    state             vms.credential_state NOT NULL DEFAULT 'requested',
    issued_at         timestamptz,
    deactivated_at    timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_to > valid_from)
);
COMMENT ON TABLE vms.credentials IS 'Passes/credentials requested from ACS (FR-VMS-07..16)';

-- Only one active credential per visitor at a time (partial unique index)
CREATE UNIQUE INDEX ux_credentials_active_per_visitor
    ON vms.credentials (visitor_id)
    WHERE state = 'active';

-- =====================================================================
-- 7. CARD ISSUANCE & RECONCILIATION  (FR-CRD-01 .. FR-CRD-03)
-- =====================================================================

CREATE TABLE vms.card_issuances (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visitor_id       uuid        NOT NULL REFERENCES vms.visitors(id) ON DELETE CASCADE,
    credential_id    uuid        REFERENCES vms.credentials(id) ON DELETE SET NULL,
    acs_card_id      text        NOT NULL,     -- physical card id reported by ACS
    issued_at        timestamptz NOT NULL DEFAULT now(),
    issued_by        uuid        REFERENCES vms.users(id) ON DELETE SET NULL,
    returned_at      timestamptz,
    returned_by      uuid        REFERENCES vms.users(id) ON DELETE SET NULL,
    is_reconciled    boolean     NOT NULL DEFAULT false,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (returned_at IS NULL OR returned_at >= issued_at)
);
COMMENT ON TABLE vms.card_issuances IS 'RFID card issue/return tracking & reconciliation (FR-CRD-01..03)';

-- =====================================================================
-- 8. ACCESS / EXIT EVENTS  (ACS -> VMS)  (FR-ENT-06/07)
-- =====================================================================

CREATE TABLE vms.access_events (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visitor_id        uuid        REFERENCES vms.visitors(id) ON DELETE SET NULL,
    credential_id     uuid        REFERENCES vms.credentials(id) ON DELETE SET NULL,
    acs_event_id      text        NOT NULL UNIQUE,   -- idempotency key from ACS
    acs_credential_id text,
    gate_ref          text,
    direction         vms.access_direction NOT NULL,
    event_time        timestamptz NOT NULL,
    received_at       timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.access_events IS 'Entry/exit events received from ACS; acs_event_id enforces idempotency (FR-ENT-06/07)';

-- =====================================================================
-- 9. ACS INTEGRATION SUPPORT  (FR-API-01 .. FR-API-05)
-- =====================================================================

-- Durable outbox / retry record for outbound ACS operations. Complements
-- the message bus: every outbound call is recorded so it can be retried,
-- audited, or dead-lettered (FR-API-03/04).
CREATE TABLE vms.acs_requests (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    operation         vms.acs_op        NOT NULL,
    credential_id     uuid        REFERENCES vms.credentials(id) ON DELETE SET NULL,
    request_payload   jsonb,
    response_payload  jsonb,
    status            vms.acs_req_status NOT NULL DEFAULT 'pending',
    attempt_count     integer     NOT NULL DEFAULT 0,
    last_error        text,
    next_retry_at     timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.acs_requests IS 'Durable log/retry queue for outbound ACS API calls (FR-API-03/04)';

-- Raw API call log for troubleshooting (FR-API-04)
CREATE TABLE vms.acs_api_log (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    acs_request_id uuid        REFERENCES vms.acs_requests(id) ON DELETE SET NULL,
    direction      text        NOT NULL,      -- 'outbound' | 'inbound'
    http_status    integer,
    payload        jsonb,
    logged_at      timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.acs_api_log IS 'Request/response audit trail for ACS integration (FR-API-04)';

-- =====================================================================
-- 10. NOTIFICATIONS  (FR-NOT-01 .. FR-NOT-05)
-- =====================================================================

CREATE TABLE vms.notification_logs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    visitor_id      uuid        REFERENCES vms.visitors(id) ON DELETE SET NULL,
    host_id         uuid        REFERENCES vms.hosts(id)    ON DELETE SET NULL,
    recipient       text        NOT NULL,
    channel         vms.notify_channel NOT NULL,
    notify_type     vms.notify_type    NOT NULL,
    subject         text,
    body_ref        text,       -- template id or storage ref, not full body
    delivery_status vms.delivery_status NOT NULL DEFAULT 'queued',
    sent_at         timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.notification_logs IS 'Outbound notification log (FR-NOT-01..05)';

-- =====================================================================
-- 11. SYSTEM SETTINGS & AUDIT  (FR-SET-01, FR-AUD-01/02)
-- =====================================================================

CREATE TABLE vms.system_settings (
    key          text PRIMARY KEY,
    value        jsonb       NOT NULL,
    description  text,
    updated_by   uuid        REFERENCES vms.users(id) ON DELETE SET NULL,
    updated_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.system_settings IS 'Application-wide settings (FR-SET-01)';

CREATE TABLE vms.audit_logs (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      uuid        REFERENCES vms.users(id) ON DELETE SET NULL,
    action       text        NOT NULL,        -- e.g. 'credential.deactivate', 'masterdata.update'
    entity_type  text,
    entity_id    text,
    before_state jsonb,
    after_state  jsonb,
    ip_address   inet,
    created_at   timestamptz NOT NULL DEFAULT now()
);
COMMENT ON TABLE vms.audit_logs IS 'Append-only audit trail (FR-AUD-01); surfaced via FR-REP-06';

-- =====================================================================
-- 12. Indexes for common access paths
-- =====================================================================
CREATE INDEX idx_floors_building        ON vms.floors(building_id);
CREATE INDEX idx_tenants_floor          ON vms.tenants(floor_id);
CREATE INDEX idx_receptions_floor       ON vms.receptions(floor_id);
CREATE INDEX idx_users_role             ON vms.users(role_id);
CREATE INDEX idx_hosts_tenant           ON vms.hosts(tenant_id);
CREATE INDEX idx_requests_tenant        ON vms.visitor_requests(tenant_id);
CREATE INDEX idx_requests_status        ON vms.visitor_requests(status);
CREATE INDEX idx_visitors_request       ON vms.visitors(request_id);
CREATE INDEX idx_visitors_status        ON vms.visitors(status);
CREATE INDEX idx_visitors_appt          ON vms.visitors(appointment_from, appointment_to);
CREATE INDEX idx_credentials_visitor    ON vms.credentials(visitor_id);
CREATE INDEX idx_credentials_state      ON vms.credentials(state);
CREATE INDEX idx_credentials_acsid      ON vms.credentials(acs_credential_id);
CREATE INDEX idx_cards_visitor          ON vms.card_issuances(visitor_id);
CREATE INDEX idx_cards_open             ON vms.card_issuances(returned_at) WHERE returned_at IS NULL;
CREATE INDEX idx_access_visitor         ON vms.access_events(visitor_id);
CREATE INDEX idx_access_time            ON vms.access_events(event_time);
CREATE INDEX idx_acsreq_status          ON vms.acs_requests(status, next_retry_at);
CREATE INDEX idx_notif_visitor          ON vms.notification_logs(visitor_id);
CREATE INDEX idx_audit_user_time        ON vms.audit_logs(user_id, created_at);
CREATE INDEX idx_audit_entity           ON vms.audit_logs(entity_type, entity_id);

-- =====================================================================
-- 13. updated_at triggers
-- =====================================================================
DO $$
DECLARE t text;
BEGIN
    FOR t IN
        SELECT unnest(ARRAY[
            'buildings','floors','tenants','receptions','visitor_types','pass_types',
            'holiday_calendar','roles','users','hosts','visitor_requests','visitors',
            'credentials','card_issuances','acs_requests','notification_logs'
        ])
    LOOP
        EXECUTE format(
            'CREATE TRIGGER trg_%1$s_updated BEFORE UPDATE ON vms.%1$s
             FOR EACH ROW EXECUTE FUNCTION vms.set_updated_at();', t);
    END LOOP;
END $$;
