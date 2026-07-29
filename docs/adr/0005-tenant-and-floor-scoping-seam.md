# ADR-0005 — Tenant and floor data scoping seam

**Status:** Provisional — the mechanism is settled, the policy is not
**Date:** 2026-07-29
**Story:** US-03.4.1 · **Open question:** TODO-14 · **Supersedes on policy:** the backlog's AC-3
**Justified against:** NFR-SEC-01 (SRS B1)

---

## Context

**No source document says whether Tenant A may see Tenant B's visitor data**, or whether a floor
receptionist is limited to their own floor. TODO-14 records that gap and it is still open.

Scoping is unusually expensive to retrofit. It is not one decision in one place — it is a condition
on every query that touches a tenant's rows, and the cost of adding it late is proportional to how
many such queries exist by then. So the seam is built now, while there are two.

## Decision

### The mechanism (settled)

| Piece | Role |
|---|---|
| `ScopedEntity` | The enum of types subject to isolation — visitor requests, visitors, hosts, the user list |
| `ScopeContext` | Who is asking; a port, so the application layer imports no framework |
| `ScopeFilter` | What they may see: `DenyAll`, `Unrestricted`, `OwnTenant`, `OwnReception` — sealed |
| `ScopePolicy` | **The only place the rule is decided** |
| `VisitorScopeSql` | Translates a filter to SQL for one table's columns |

`ScopeFilter` is deliberately **not** SQL. The same rule is `tenant_id` on `visitor_requests` and a
join through the request on `visitors`; a policy that emitted SQL would have to know every table it
might ever apply to, and the application layer would be writing queries.

### The policy (provisional)

**Strict own-scope**, per [ADR-0004](0004-master-data-and-rbac-as-approved-enablers.md):

| Role | Sees |
|---|---|
| `MASTER_ADMIN`, `FM_ADMIN`, `SYSTEM_ADMIN` | Everything — they approve and administer across the building |
| `TENANT` | Their own tenant's rows |
| `FLOOR_RECEPTIONIST` | Their own reception's rows |
| anyone else, or scope undeterminable | **Nothing** |

## Where this diverges from the backlog, and why

`US-03.4.1` AC-3 asks for a **deny-all** default, with the instruction that *"no permissive scoping
rule may be written until TODO-14 is dispositioned"*.

**Deny-all is not implementable as a default here.** It would prevent a tenant seeing *their own*
visitor requests, which makes the tenant-facing half of EPIC-07 — a requirement backed by
FR-VMS-01 (SRS B1) — impossible to build. The backlog and the SRS-backed work are in conflict, and
ADR-0004 resolved it before this story started.

What survives from AC-3 is its actual intent, kept everywhere it costs nothing:

- An entity type the policy does not recognise → **nothing**.
- A principal whose scoping column is null → **nothing**. A tenant user with no tenant is not
  unrestricted; they are undeterminable, and the safe reading of undeterminable is nothing.
- A role nobody has classified → **nothing**.

So the failure modes all fail closed. What changed is that a *correctly determined* tenant sees
their own data instead of nothing.

## Consequences

**If TODO-14 answers "strict"** — nothing changes.

**If it answers "permissive"** — widening happens in `ScopePolicy` and nowhere else.

**If it answers something structural** (a tenant group, a delegation) — `ScopeFilter` gains a case.
It is sealed, so every place that handles a filter becomes a compile error until updated. That is the
intended cost: a new isolation shape should not be quietly ignorable by an adapter written before it
existed.

**The bypass is what the fitness rule guards.** `ScopingRulesTest` fails the build naming any
infrastructure class that touches a scope-sensitive type without consulting `ScopePolicy`, and
forbids anything outside the policy from constructing `Unrestricted`. That rule matters more than the
policy: the policy is one reviewable class, while a bypass could be anywhere, and the fitness rule
has been verified by seeding a violation and watching the build fail.

**A per-request cost, deferred.** The tenant and reception are read from `vms.users` rather than the
token, so moving a user between tenants applies on their next request rather than at their next
login — a stale scope is an isolation failure and a session lasts hours. The read is lazy: requests
that never touch scoped data never pay for it.

## Amendment (US-07.3.1): the posture is configuration, and there is still only one seam

US-07.3.1 AC-6 asks that restricting an approver to a subset of tenants or floors later require **no
change to query-building code**, and `T-07.3.1.2` phrases the mechanism as a separate
`ApprovalScopeStrategy` port with a permissive default and a restrictive alternative.

**We did not add a second port.** A dedicated strategy for approval visibility would give isolation
two homes — the thing this ADR and `ScopingRulesTest` exist to prevent — and worse, the queue would
then be scoped by different rules from the request-detail read sitting behind every row in it. Two
scoping mechanisms that agree today are two that can disagree after one is edited.

What was added instead is a **posture on `ScopePolicy`**, selected by `vms.scoping.posture`:

| Value | Building-wide roles (`MASTER_ADMIN`, `FM_ADMIN`, `SYSTEM_ADMIN`) |
|---|---|
| `building-wide` *(default)* | `Unrestricted` — an approver cannot decide what they cannot see |
| `own-scope` | confined to their own tenant, else their own reception, else nothing |

This satisfies the AC as written: the queue's SQL contains no tenant condition, the swap is a
property, and `ApprovalQueueIT` demonstrates it by running the same store against both postures on a
real database and observing a filtered result. The active posture is logged at startup, so the
isolation a deployment is enforcing is discoverable from its logs rather than only from its config.

**`own-scope` denies an approver who has no assignment at all**, which today is every FM Admin —
they are not tenant-scoped. That is the honest answer rather than an oversight: if approvers are to
be restricted to a subset, someone has to say which subset, and until TODO-14 is answered there is no
defensible set to show. It fails closed, which is the direction this ADR has chosen throughout.

## Status of TODO-14

**Still open.** This ADR does not close it. It records that the mechanism is in place, that the
policy shipped is provisional, and that changing it is a change to one class — or, since US-07.3.1,
a change to one property.
