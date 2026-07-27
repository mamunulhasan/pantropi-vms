# ADR 0004 — Master data and RBAC administration as approved enablers

**Status:** Accepted
**Date:** 2026-07-28
**Deciders:** Repository owner (client-side authority), Lead Architect
**Requirement:** *None directly.* Enables FR-VMS-01…03, FR-ADM-01/02, NFR-SEC-01 (SRS B1)
**Supersedes the hold placed by:** [ADR-0003](0003-requirements-baseline-b1.md), D-03, D-05, TODO-01

## Context

EPIC-03 (roles, permission grants, data scoping) and EPIC-04 (building, floor, tenant, reception,
visitor type, pass type, holiday calendar, system settings) are **entirely `TDD-DERIVED`**. They are
described by the TDD (§4.1, §7) and implied by the database schema, but no functional requirement in
the attached SRS asks for them. Under project rule 2 — *never implement functionality that is not in
the SRS* — they were held in `status/blocked` for the whole of Phase 1.

That hold has now produced a concrete defect. US-07.1.1 (FR-VMS-01, SRS B1) submits a visitor
request against `vms.tenants` and `vms.hosts`, and US-02.2.2 provisions reception accounts against
`vms.receptions` — but **nothing in the system can create any of those rows**. Every integration
test seeds them by direct SQL. The SRS-backed features are therefore unusable in a real deployment,
not because they are incomplete, but because their reference data has no way in.

[04-milestones-and-release-plan.md](../project/04-milestones-and-release-plan.md) anticipated this
and pre-agreed the mechanism: build a master-data slice, record it as a deviation, **with client
sign-off — never unilaterally**.

## Decision

**TODO-01 is dispositioned as follows**, on the authority of the repository owner:

1. **The attached SRS remains the requirements baseline (B1).** SRS v2 is not available and is not
   awaited further. Nothing in this decision authorises inventing *user-facing* behaviour.
2. **Master data management (EPIC-04) and RBAC administration (EPIC-03) are approved enablers.**
   They are authorised because SRS-backed features cannot function without them, in the same way
   EPIC-01 (build pipeline) is an approved enabler justified by NFR-MNT-01 rather than by an FR.
3. **Their scope is bounded by what the schema already defines.** These epics may implement CRUD and
   lifecycle for the entities present in `docs/vms_schema_postgresql.sql` and nothing beyond them. A
   new entity, a new workflow, or a new user-facing capability still requires a requirement.
4. **Provenance changes from `TDD-DERIVED` to `ENABLER`** for these epics in the backlog index and
   the traceability matrix, so the reverse trace reports them as approved rather than unbacked.

### What is *not* authorised

- Inventing behaviour the schema does not imply — e.g. approval workflows on master data,
  soft-delete semantics beyond the existing `is_active` flag, or bulk operations beyond those an
  SRS requirement already needs.
- Building `FR-ANL-01` / `FR-EXP-01` (EPIC-19) or the TDD-only notification scenarios
  (FR-NOT-03/04/05). Those remain `TDD-DERIVED` and blocked — they add user-facing capability
  rather than enabling an existing requirement.

## Tenant and floor data scoping (TODO-14)

TODO-14 — whether Tenant A may see Tenant B's visitor data, and whether a floor receptionist is
limited to their own floor — remains **unanswered by any source document**. It is not resolved here.

**Interim decision:** build the scoping *seam* with a **strict default** — a tenant sees only its own
data; a floor receptionist only their own floor. Rationale:

- A restrictive default fails safe. If the eventual policy is more permissive, widening it is a
  configuration change; if we defaulted permissive and the policy turns out to be strict, we would
  have leaked tenant data in the meantime.
- Scoping applied at the repository/query layer is very expensive to retrofit, and cheap to relax.

The policy is expressed as a swappable component, so dispositioning TODO-14 changes the policy, not
the plumbing. TODO-14 stays open.

## Consequences

**Positive**
- The SRS-backed features already delivered become usable: someone can create a tenant, a floor, a
  reception, and a host without direct SQL.
- The reverse trace becomes truthful again — these features are marked approved, with this ADR as
  the authority, instead of sitting permanently as unbacked work in a blocked epic.
- Unblocks the remainder of Phase 1 and removes a dependency drag on Phase 2.

**Negative**
- The delivered system will contain administrative capability the client never wrote a requirement
  for. If SRS v2 later contradicts what we built (different fields, different lifecycle), rework is
  possible. The bound in decision 3 keeps that exposure to the schema's own shape, which the client
  supplied, so the risk is materially lower than free invention.
- The precedent must not be over-read. This authorises *enablers for existing requirements*, not a
  general licence to build from the TDD. D-03's remaining items stay blocked.

**Reversal**
If SRS v2 arrives and contradicts this, the affected epics are re-baselined like any other
requirement change (governance §Requirements change control). The work is confined behind ports and
per-entity adapters, so a changed field set is an adapter and migration change.
