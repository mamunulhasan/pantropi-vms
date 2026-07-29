-- US-07.3.2 (T-07.3.2.1) — searching the approval queue by visitor or host name.
--
-- The search is a substring match, because an approver looking for "Lovelace" should find
-- "Ada Lovelace" — and a leading wildcard makes a btree index useless. `LIKE '%x%'` on a plain
-- index is a sequential scan of every visitor row in the building, which is fine at a hundred rows
-- and is not fine at the volume this queue is meant to save someone from paging through.
--
-- pg_trgm indexes trigrams rather than prefixes, so it answers a leading-wildcard match from an
-- index. It is the reason the search can be offered at all rather than being a feature that degrades
-- as the system fills up.
--
-- The extension goes in `public` deliberately: V11 records at length what happens when an extension
-- lands in `vms` and application connections cannot resolve its operators. Here the consequence
-- would be milder — the planner would ignore the index rather than answer a query wrongly — but the
-- rule is the same and worth keeping uniform.

CREATE EXTENSION IF NOT EXISTS pg_trgm SCHEMA public;

-- gin_trgm_ops rather than gist: GIN is slower to update and faster to search, and these names are
-- written once when a request is raised and searched every time an approver opens the queue.
CREATE INDEX IF NOT EXISTS idx_visitors_full_name_trgm
    ON vms.visitors USING gin (full_name gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_hosts_full_name_trgm
    ON vms.hosts USING gin (full_name gin_trgm_ops);

COMMENT ON INDEX vms.idx_visitors_full_name_trgm IS
    'Substring search on the approval queue (US-07.3.2 AC-2). A visitor name is searchable but is '
    'never returned by the queue projection — the list shows counts, not people.';
