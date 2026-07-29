-- US-07.1.2 (AC-1) — and a defect it uncovered that reaches much further than visitor email.
--
-- V1 creates the citext extension while Flyway's connection has search_path = vms, so the type and
-- **its operators** land in the vms schema. Application connections run with the PostgreSQL default
-- search_path of "$user", public — which does not include vms — so the citext '=' operator is not
-- resolvable at query time. PostgreSQL then falls back to text equality via binary coercion, and
-- every comparison on a citext column is silently CASE-SENSITIVE.
--
-- Demonstrated on a fresh database built from V1–V10:
--
--     SHOW search_path;                              -->  "$user", public
--     SELECT 'ABC'::vms.citext = 'abc'::vms.citext;  -->  false
--
-- It is worth being precise about what this broke, because it is not only email:
--
--   * vms.users.username — a user created as 'alice' could not log in as 'Alice'.
--   * vms.login_attempts.username — V8 records the citext key as load-bearing "so alice and Alice
--     share a counter". They did not. Varying the capitalisation of a username reset the lockout
--     counter, which turns a bounded number of attempts into an unbounded one. That is the reason
--     this migration is not filed as a tidy-up.
--   * vms.users.email, vms.tenants.contact_email, vms.hosts.email, vms.visitors.email.
--
-- The UNIQUE indexes were NOT affected, which is what made this invisible: an index records its
-- operator class by OID when it is built, so uniqueness on username stayed case-insensitive. The
-- system therefore refused to create 'Alice' alongside 'alice' while also refusing to let 'Alice'
-- log in — the two halves disagreed, and nothing failed loudly.
--
-- The fix is to put the extension where every connection can already see it, rather than to change
-- the search_path of each. A search_path is per-connection configuration: it would have to be set on
-- the application datasource, on Flyway, on any migration tool, and on every psql session a person
-- opens to look at the data — and the failure mode of forgetting one is silent wrong answers rather
-- than an error. Moving the extension once fixes it for all of them.
--
-- Existing indexes and constraints are unaffected: they reference the operator class by OID, which
-- does not change when the extension moves.

DO $$
DECLARE
    ext_schema text;
BEGIN
    SELECT n.nspname INTO ext_schema
      FROM pg_extension e
      JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'citext';

    IF ext_schema IS NULL THEN
        -- Not installed at all: create it where it belongs. Should not happen after V1, but a
        -- migration that assumes its predecessor's side effects is one that fails on the one
        -- database that was restored differently.
        CREATE EXTENSION citext SCHEMA public;
    ELSIF ext_schema <> 'public' THEN
        ALTER EXTENSION citext SET SCHEMA public;
    END IF;
    -- Already in public: nothing to do, and re-running this migration is a no-op.
END
$$;
