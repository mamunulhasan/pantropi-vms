# ⚠️ SUPERSEDED — Epics & Backlog (14-epic scheme)

**Superseded:** 2026-07-19 · **Replaced by:** [`backlog/00-epic-feature-index.md`](backlog/00-epic-feature-index.md)

---

## Do not use this document

This file described an earlier **14-epic, M0–M6 milestone** decomposition, produced before the
project adopted the client's four-phase structure. Its epic IDs **conflict** with the current
scheme — the same ID means different things in each:

| ID | Old meaning (this document) | Current meaning |
|---|---|---|
| `EPIC-05` | Arrival Verification & Credential Issuance | Audit, Security & Compliance Foundation |
| `EPIC-07` | Credential Validity & Lifecycle | Visitor Request & Approval |
| `EPIC-09` | Card Accountability & Reconciliation | Pass & Credential Generation |
| `EPIC-10` | Notifications & Alerts | Host Management |
| `EPIC-11` | Reporting & Analytics | ACS Integration Client |
| `EPIC-12` | ACS Integration Client | Arrival Verification & Entry |
| `EPIC-14` | Audit & Observability | Credential Lifecycle Operations |

Reading a bare epic ID against this document will point you at the wrong work. It is retained only
so that any external reference to the old numbering can be resolved via the table above.

## Current documents

| You want… | Read |
|---|---|
| The epic and feature skeleton — 19 epics, 93 features | [`backlog/00-epic-feature-index.md`](backlog/00-epic-feature-index.md) |
| Full story and task decomposition | [`backlog/`](backlog/) — one file per phase |
| Requirement → delivery traceability | [`02-traceability-matrix.md`](02-traceability-matrix.md) |
| Milestones, releases and exit criteria | [`12-release-plan.md`](12-release-plan.md) |
| What may be built at all | [`01-requirements-catalogue.md`](01-requirements-catalogue.md) |

## Why this happened

The first baseline organised work around the SRS's own structure (28 requirements, no phasing). The
client subsequently specified a four-phase delivery structure, which aligns with the TDD's four
service domains (§4.1–§4.5) and is most likely the phasing referenced by the missing SRS v2 (D-01).

Re-baselining the epics was the right call, but it invalidated every epic ID in this document. The
lesson is recorded in [`08-source-document-discrepancies.md`](08-source-document-discrepancies.md):
**epic IDs are not stable across a re-baseline, which is exactly why requirement references must be
qualified** (`FR-VMS-01 (SRS B1)`) rather than written bare.
