# ADR 0003 — Adopt the attached SRS as requirements baseline B1

**Status:** Provisional
**Date:** 2026-07-19
**Deciders:** Lead Architect, Technical Product Manager
**Depends on:** TODO-01
**Related:** D-01, D-02, D-03, D-04, D-05

## Context

Three source documents were supplied as the single source of truth. They are not the same
generation:

- The **TDD** states its basis is *"VMS SRS v2 (96 functional requirements across four phases)."*
- The attached **SRS** contains **28** functional requirements and describes no phasing.
- The **schema** and TDD reference roughly **50 requirement IDs** (`FR-CFG-*`, `FR-ENT-*`,
  `FR-USR-*`, `FR-AUTH-*`, `FR-AUD-*`, `FR-SET-*`, `FR-ANL-*`, `FR-EXP-*`, `FR-NTF-*`, and others)
  that are **not defined in the attached SRS**.
- Where IDs overlap, they sometimes mean different things — `FR-VMS-10` is express entry in the SRS
  but `FR-ENT-03` in the TDD; the `FR-API-*` series is shifted by one.

We cannot build the system the TDD describes without inventing ~50 requirements. Project rule 2
forbids that.

## Decision

We adopt the **attached SRS as requirements baseline B1**, and treat it as authoritative for both
the *existence* and the *meaning* of every requirement id.

Specifically:

1. **The 28 SRS functional requirements are the buildable scope.** They are catalogued with
   provenance in `docs/project/01-requirements-catalogue.md`.
2. **Where the SRS and TDD assign different meanings to the same id, the SRS wins.** All traceability
   references are qualified — `FR-VMS-01 (SRS B1)` — so a future re-baseline does not silently
   invalidate historical commits and PRs.
3. **The TDD and schema are treated as design input, not requirements.** They inform *how* we build
   what the SRS asks for. They may not, on their own, justify building anything.
4. **Requirement ids referenced but undefined are catalogued, not implemented.** They appear in
   catalogue §5 for visibility. Epics covering them (EPIC-03, parts of EPIC-14) exist but are held
   in `status/blocked`.
5. **A reverse trace runs every sprint review** to catch anything being built without a requirement.

## Assumption

We are assuming SRS v2 either does not exist in a usable form, or will be supplied. **This must be
confirmed by CPG Corporation / Pantropi document control (TODO-01).**

**If SRS v2 is supplied:** we re-baseline to B2, re-catalogue all 96 requirements, remap the
traceability matrix, and unblock EPIC-03 and EPIC-14. Work completed under B1 remains valid — the
28 requirements are a subset — but ids may need remapping, which is why qualified references matter.

**If the attached SRS is confirmed as final:** the TDD and schema are reduced to match it. Roughly
14 of 20 schema tables and the whole Configuration & Master Data Service would fall out of scope, or
require new requirements to be written and approved before they can be built.

These two outcomes have very different costs. This is why TODO-01 is the highest-priority open item
on the project.

## Alternatives considered

**Reverse-engineer requirements from the TDD and schema.** They are detailed enough to make this
tempting — the schema even carries inline `FR-` comments. But it means writing requirements the
client never approved, then building against them. That is the exact failure project rule 2 exists
to prevent, and it would produce a system nobody signed off on. Rejected.

**Halt entirely until TODO-01 resolves.** Defensible, but 9 of 28 requirements are ready to
implement today and depend on nothing external. Idling the team while a document is located would
waste the one window in which unblocked work is available. Rejected.

**Treat the union of all three documents as the requirement set.** Maximises apparent scope and
guarantees we build unbacked functionality, with no way to tell approved requirements from inferred
ones. Rejected.

## Consequences

**Positive** — every implemented requirement is traceable to a client-approved document. Scope is
honest and defensible. Work can start immediately on the unblocked 32%.

**Negative** — delivered scope is a fraction of what the TDD describes, which may surprise
stakeholders reading the TDD. The milestone plan and traceability matrix state this explicitly so
the gap is visible rather than discovered late.

**Re-baselining cost** — if SRS v2 arrives after M2, the traceability matrix needs a full remap.
Qualified requirement references (`FR-VMS-01 (SRS B1)`) are the mitigation, and are mandatory in
commits and PRs from day one for exactly this reason.
