# Milestones & Release Plan — Baseline B1

**Chain position:** Requirements → **GitHub Milestone** → Epic → Feature → User Story → …

Milestones in GitHub map 1:1 to the phases below. Every issue carries exactly one milestone.

> **No calendar dates are committed in this baseline.** Two blocking dependencies (TODO-01, TODO-02)
> sit outside Pantropi's control. Publishing dates before they resolve would be a fiction. Dates are
> attached to M1 at kickoff and to M3+ once the ACS API contract lands.

---

## M0 — Inception & Requirements Baseline

**Goal:** establish a defensible, traceable baseline and surface every dependency before code.
**Exit criteria:**

- [x] All three source documents analysed and catalogued
- [x] 28 SRS FRs, 8 NFRs, 3 constraints registered with provenance
- [x] Requirements traceability matrix established (100% coverage)
- [x] Epic → Feature → Story decomposition for all unblocked scope
- [x] 9 discrepancies raised (D-01 … D-09)
- [x] 19 open questions raised (TODO-01 … TODO-19)
- [x] GitHub project scaffolding — templates, labels, milestones, workflow
- [ ] **Client disposition on TODO-01 (SRS v2) and TODO-19 (gate decision authority)**
- [ ] **UAL disposition on TODO-02 (ACS API contract) and TODO-03 (express entry)**

**Status:** Pantropi deliverables complete. Awaiting client and vendor input.

---

## M1 — Foundation, Identity & Master Data

**Goal:** a deployable, secured, empty application — no business workflow yet, but everything a
workflow needs.

| Epic | Scope | Status |
|---|---|---|
| EPIC-01 | Platform foundation & delivery pipeline | Ready |
| EPIC-02 | Identity, authentication & RBAC | Ready — TODO-08, TODO-15 |
| EPIC-03 | Configuration & master data | 🔴 Blocked — TODO-01 |
| EPIC-14 | Audit, security & observability (partial) | 🟠 Partial — TODO-01 |

**Requirements delivered:** FR-ADM-01, FR-ADM-02 · **Supports:** NFR-SEC-01, NFR-MNT-01, NFR-SCL-01

**Exit criteria:**
- CI/CD green: build, unit tests, integration tests, coverage gate, SAST
- Clean Architecture boundaries enforced by automated fitness tests
- Database migrations run cleanly from empty to baseline schema
- A user can log in, receive a session, and be denied an unauthorised endpoint
- Deployable to a staging environment from a merged commit

**Risk:** EPIC-03 blocked means visitor requests have no Tenant/Host/Reception to reference. If
TODO-01 does not resolve before M2, we build a **minimal master-data slice** — exactly the entities
EPIC-04 requires, no more — and record it as a deviation. That decision needs client sign-off; it is
not taken unilaterally.

---

## M2 — Visitor Request & Approval Workflow

**Goal:** the complete pre-arrival journey, end to end, with no ACS involvement.

| Epic | Scope | Status |
|---|---|---|
| EPIC-04 | Visitor request & approval workflow | Ready |
| EPIC-13 | Portal branding & design system | Ready — brand assets needed |

**Requirements delivered:** FR-VMS-01, FR-VMS-02, FR-VMS-03, FR-ADM-03, CON-03

**Exit criteria:**
- A tenant submits a request; an FM Admin approves or rejects it; a floor receptionist pre-registers
  a visitor to the central server — all demonstrable end to end
- Approval emits a domain event consumable by downstream services
- Portal renders under the client brand
- **This milestone is independently demonstrable to the client with zero ACS dependency.**

**Why this ordering matters:** M2 delivers real, visible client value while TODO-02 is unresolved.
It is deliberately the largest unblocked slice of the SRS.

---

## M3 — ACS Integration & Credential Lifecycle 🔴 GATED

**Gate:** TODO-02 (ACS API contract) must be resolved before this milestone can close.

| Epic | Scope | Status |
|---|---|---|
| EPIC-12 | ACS integration client & reliability | 🟠 Partial — F-12.9 gated |
| EPIC-05 | Arrival verification & credential issuance | 🔴 Blocked |
| EPIC-07 | Credential validity & lifecycle | 🔴 Blocked |

**Requirements delivered:** FR-VMS-04..07, FR-VMS-11..14, FR-API-01, FR-API-02, FR-API-03

**Two-stage exit:**

*Stage A — buildable now:*
- ACS port defined; no domain service references an ACS type (fitness test enforces)
- ACS simulator with configurable latency and failure injection
- Retry queue, dead-letter handling, receptionist notification, full request/response audit logging
- All credential workflows pass integration tests **against the simulator**

*Stage B — requires TODO-02:*
- Wire-level adapter implemented against the published UAL contract
- End-to-end verification against the ACS non-production endpoint
- NFR-PRF-01 measured (requires TODO-07 target)

**Stage A can start immediately and is worth doing regardless** — it is what makes NFR-MNT-01 real,
and it means Stage B is an adapter swap rather than a rewrite.

---

## M4 — Reception Operations 🔴 GATED

**Gate:** M3 Stage B.

| Epic | Scope | Status |
|---|---|---|
| EPIC-06 | Walk-in & express entry | 🔴 Blocked — TODO-03 may descope F-06.4 |
| EPIC-08 | Visitor-facing reception display | Ready — TODO-17 |
| EPIC-09 | Card accountability & reconciliation | 🔴 Blocked — TODO-11 |

**Requirements delivered:** FR-VMS-08, 09, 10, 15; FR-CRD-01, 02, 03

**Scope risk:** FR-VMS-10 (express entry) may be removed entirely per TODO-03. Do not plan capacity
against it. EPIC-08 is unblocked and can pull forward into M2 if M3 slips.

---

## M5 — Notifications & Reporting

| Epic | Scope | Status |
|---|---|---|
| EPIC-10 | Notifications & alerts | 🟠 Email ready; WhatsApp gated on TODO-05 |
| EPIC-11 | Reporting & analytics | 🟠 Partial — ACS event data gated on TODO-02 |

**Requirements delivered:** FR-NOT-01, FR-NOT-02, FR-REP-01, FR-REP-02

**Partial-pull opportunity:** the email notification path (F-10.1–F-10.4) depends only on VMS domain
events for the approval journey. Those stories can pull into **M2** and should, if M3 is delayed —
it converts idle time into shipped requirement coverage.

---

## M6 — Hardening & Release 1.0

**Goal:** production readiness.

**Exit criteria:**
- NFR verification complete: performance (NFR-PRF-01), load at 500 visitors/day + 120 concurrent
  users (NFR-SCL-01), availability (NFR-AVL-01), ACS-independence
- OWASP ASVS review passed; penetration test findings remediated
- Data protection impact assessment signed off (NFR-CMP-01, TODO-06, TODO-12)
- Traceability matrix 100% populated through the PR and Release columns
- Operational runbook, deployment documentation, and user training material complete
- Warranty/maintenance terms defined (SRS §8)

---

## Dependency-critical path

```
TODO-01 (SRS v2) ──────────► EPIC-03 ──► M1 exit ──► M2 ──────────────► client demo
                                                      │
TODO-02 (ACS contract) ─────► EPIC-12 F-12.9 ──► M3 Stage B ──► M4 ──► M6
                                    ▲
                          EPIC-12 Stage A (buildable now)
TODO-19 (gate authority) ──► deployment architecture sign-off ──► M6
TODO-03 (express entry) ───► F-06.4 scope decision ──► M4
```

**The two critical-path items are both external.** TODO-01 sits with the client/document owner;
TODO-02 sits with UAL. Everything Pantropi can build without them is scheduled into M1 and M2.

---

## Release strategy

| Release | Contains | Deployable independently |
|---|---|---|
| 0.1.0 | M1 — foundation, identity | Yes — staging only |
| 0.2.0 | M2 — request & approval workflow | Yes — **client-demonstrable** |
| 0.3.0 | M3 Stage A — ACS port + simulator | Yes — no user-visible change |
| 0.4.0 | M3 Stage B — live ACS integration | Requires ACS endpoint |
| 0.5.0 | M4 — reception operations | Yes |
| 0.6.0 | M5 — notifications & reporting | Yes |
| **1.0.0** | M6 — hardened production release | Yes |

Semantic versioning. Every feature ships behind a flag and is independently deployable, per project
rules. Release notes are generated from Conventional Commit history and cross-referenced to the
traceability matrix.
