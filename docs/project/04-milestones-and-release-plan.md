# ⚠️ SUPERSEDED — Milestones & Release Plan (M0–M6 scheme)

**Superseded:** 2026-07-19 · **Replaced by:** [`12-release-plan.md`](12-release-plan.md)

---

## Do not use this document

This file described an earlier **M0–M6 milestone** sequence, produced before the project adopted the
client's four-phase structure. It also references the superseded 14-epic numbering
(see [`03-epics-and-backlog.md`](03-epics-and-backlog.md)) and at least one feature ID — `F-12.9`
for the ACS wire adapter — that exists in neither scheme. The current ID is **F-11.7**.

## Milestone mapping

| Old milestone | Current milestone |
|---|---|
| M0 — Inception & Requirements Baseline | *(complete — folded into baseline B1)* |
| M1 — Foundation, Identity & Master Data | **Phase 1 — Platform Foundation** |
| M2 — Visitor Request & Approval Workflow | **Phase 2 — Visitor Registration & Pass Generation** |
| M3 — ACS Integration & Credential Lifecycle | **Phase 3 — Entry Verification & ACS Integration** |
| M4 — Reception Operations | **Phase 3** (merged) |
| M5 — Notifications & Reporting | **Phase 4 — Reporting & Notification** |
| M6 — Hardening & Release 1.0 | **Release 1.0 — Hardening & Production** |

The Stage A / Stage B split within the ACS phase carried over unchanged, and remains the core
scheduling device for working around the unpublished ACS contract (TODO-02).

## Current documents

| You want… | Read |
|---|---|
| Milestones, release contents, exit criteria, rollback | [`12-release-plan.md`](12-release-plan.md) |
| The epic and feature skeleton | [`backlog/00-epic-feature-index.md`](backlog/00-epic-feature-index.md) |
| Story and task detail per phase | [`backlog/`](backlog/) |
| Branching, environments, protection rules | [`10-branch-strategy.md`](10-branch-strategy.md) |
