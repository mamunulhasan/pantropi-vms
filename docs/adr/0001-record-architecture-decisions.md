# ADR 0001 — Record architecture decisions

**Status:** Accepted
**Date:** 2026-07-19
**Deciders:** Lead Architect

## Context

Project Pinnacle VMS has significant unresolved external dependencies (TODO-01, TODO-02, TODO-19)
and three source documents that disagree with one another. Decisions will be taken under
uncertainty, and some will be **provisional** — made to keep work moving, pending client or vendor
confirmation.

Without a record, a provisional decision becomes an invisible assumption. That is precisely how
unrequirmented behaviour enters a system, which project rule 2 exists to prevent.

## Decision

We record architecturally significant decisions as ADRs in `docs/adr/`, numbered sequentially,
using this template.

A decision is architecturally significant if it is expensive to reverse, crosses a service or layer
boundary, affects a non-functional requirement, or **encodes an assumption about an unresolved
requirement**.

ADRs use these statuses:

| Status | Meaning |
|---|---|
| `Proposed` | Under discussion |
| `Accepted` | In force |
| `Provisional` | **In force, but rests on an unconfirmed assumption.** Must name the TODO it depends on and what changes when it resolves. |
| `Superseded by ADR-NNNN` | Replaced |
| `Deprecated` | No longer applies, not replaced |

`Provisional` is the status this project will use most. Every provisional ADR is reviewed at each
milestone exit and either promoted to `Accepted` once confirmed, or revised.

## Consequences

**Positive** — assumptions become visible and reviewable; new contributors get the reasoning, not
just the result; provisional decisions cannot quietly harden into permanent ones.

**Negative** — writing overhead. Mitigated by keeping ADRs short; a decision that needs ten pages is
usually two decisions.

## Template

```markdown
# ADR NNNN — <title>

**Status:** Proposed | Accepted | Provisional | Superseded by ADR-NNNN | Deprecated
**Date:** YYYY-MM-DD
**Deciders:** <roles>
**Requirement:** <FR/NFR ids, qualified — e.g. FR-VMS-05 (SRS B1)>
**Depends on:** <TODO ids, if Provisional>

## Context
What forces are at play? What constraint or requirement drives this?

## Decision
What we are doing, stated in the active voice.

## Assumption (Provisional ADRs only)
What we are assuming, who must confirm it, and what changes if the answer differs.

## Alternatives considered
What else was on the table, and why it lost.

## Consequences
Positive, negative, and what this makes harder later.
```
