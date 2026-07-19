# Requirements Traceability Matrix (RTM) — Baseline B1

**Chain:** Requirement → Milestone → Epic → Feature → User Story → Task → Code → Test → PR → Release

This matrix is the project's contractual record that every requirement is accounted for, and that
nothing is built without a requirement. **It is a living document** — the Task, Code, Test, PR and
Release columns are populated as work completes, and no PR may merge without updating its row.

**Baseline:** B1 — 2026-07-19 · **Scope:** 28 SRS functional requirements

---

## Forward trace — Requirement → delivery

| Requirement | Milestone | Epic | Feature | User Story | Status | Gating |
|---|---|---|---|---|---|---|
| **FR-VMS-01** | M2 | EPIC-04 | F-04.1 | US-04.1.1, US-04.1.2, US-04.1.3 | Ready | — |
| **FR-VMS-02** | M2 | EPIC-04 | F-04.2 | US-04.2.1, US-04.2.2, US-04.2.3 | Ready | — |
| **FR-VMS-03** | M2 | EPIC-04 | F-04.3 | US-04.3.1 | Ready | — |
| **FR-VMS-04** | M3 | EPIC-05 | F-05.1 | *not written* | Partial | EPIC-04 |
| **FR-VMS-05** | M3 | EPIC-05 | F-05.2 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-VMS-06** | M3 | EPIC-05 | F-05.3 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-VMS-07** | M3 | EPIC-05 | F-05.4, F-05.5, F-05.6 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-VMS-08** | M4 | EPIC-06 | F-06.1, F-06.2 | *not written* | 🔴 Blocked | TODO-18 |
| **FR-VMS-09** | M4 | EPIC-06 | F-06.3 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-VMS-10** | M4 | EPIC-06 | F-06.4 | *not written* | 🔴 Blocked | TODO-03 — *may be descoped* |
| **FR-VMS-11** | M3 | EPIC-07 | F-07.1 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-VMS-12** | M3 | EPIC-07 | F-07.2 | *not written* | 🔴 Blocked | TODO-02, TODO-09 |
| **FR-VMS-13** | M3 | EPIC-07 | F-07.3 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-VMS-14** | M3 | EPIC-07 | F-07.4 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-VMS-15** | M4 | EPIC-08 | F-08.1, F-08.2, F-08.3 | *not written* | Ready* | TODO-17 |
| **FR-CRD-01** | M4 | EPIC-09 | F-09.1 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-CRD-02** | M4 | EPIC-09 | F-09.2 | *not written* | 🔴 Blocked | TODO-02 |
| **FR-CRD-03** | M4 | EPIC-09 | F-09.3, F-09.4 | *not written* | 🔴 Blocked | TODO-02, TODO-11 |
| **FR-NOT-01** | M5 | EPIC-10 | F-10.2, F-10.3, F-10.5, F-10.6 | US-10.2.1, US-10.3.1, US-10.3.2 | Partial | TODO-05 (WhatsApp), TODO-10 |
| **FR-NOT-02** | M5 | EPIC-10 | F-10.4 | US-10.4.1 | Partial | TODO-02 (exit events) |
| **FR-REP-01** | M5 | EPIC-11 | F-11.1, F-11.3 | *not written* | Partial | TODO-02 (ACS events) |
| **FR-REP-02** | M5 | EPIC-11 | F-11.2 | *not written* | Partial | EPIC-09 |
| **FR-API-01** | M3 | EPIC-12 | F-12.3, F-12.4 | US-12.3.1, US-12.3.2 | Ready | — |
| **FR-API-02** | M3 | EPIC-12 | F-12.5 | US-12.5.1 | Ready | — |
| **FR-API-03** | M3 | EPIC-12 | F-12.6 | *not written* | Ready | — |
| **FR-ADM-01** | M1 | EPIC-02 | F-02.3 | US-02.3.1 | Ready | — |
| **FR-ADM-02** | M1 | EPIC-02 | F-02.1, F-02.4 | US-02.1.1, US-02.1.2 | Partial | TODO-08, TODO-15 |
| **FR-ADM-03** | M2 | EPIC-13 | F-13.1, F-13.2 | *not written* | Ready | Brand assets |

\* *Ready* means the requirement is clear enough to plan; a 🟡 TODO refines detail during sprint planning.

---

## Non-functional requirement trace

| Requirement | Epic(s) | Verification method | Status |
|---|---|---|---|
| NFR-PRF-01 | EPIC-12 | Performance test at reception UI | 🔴 **Untestable** — no target (TODO-07) |
| NFR-REL-01 | EPIC-12, EPIC-04 | Chaos test: ACS unavailable during issuance | Ready |
| NFR-AVL-01 | EPIC-01, EPIC-14 | Uptime monitoring; ACS-independence test | Partial — operating hours undefined |
| NFR-SEC-01 | EPIC-02, EPIC-12, EPIC-14 | OWASP ASVS review + RBAC integration tests | Ready |
| NFR-SCL-01 | EPIC-01 | Load test: 500 visitors/day, 120 concurrent users | Ready |
| NFR-USA-01 | EPIC-13, EPIC-08 | Usability review with reception staff | Partial — no acceptance criteria |
| NFR-MNT-01 | EPIC-12 | Architecture fitness test: no ACS type outside the integration module | Ready |
| NFR-CMP-01 | EPIC-01, EPIC-14 | Data protection impact assessment | 🔴 Blocked — TODO-06, TODO-12 |

| Constraint | Enforced by | Verification |
|---|---|---|
| CON-01 | EPIC-12 | Architecture fitness test — no hardware-specific types anywhere in the codebase |
| CON-02 | EPIC-12 (F-12.1) | Architecture fitness test — no credential mutation path bypasses the ACS port |
| CON-03 | EPIC-13 | Design review against client brand guidelines |

---

## Reverse trace — is anything being built without a requirement?

Run at every sprint review. Any row that cannot name a requirement is either an approved enabler or
gets closed as out-of-scope.

| Artefact | Backing requirement | Verdict |
|---|---|---|
| EPIC-01 Platform Foundation | *none* | ✅ Approved enabler — NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 |
| EPIC-14 Audit & Observability | NFR-SEC-01 (partial) | ⚠️ F-14.2 audit log is TDD/schema-only — TODO-01 |
| EPIC-03 Master Data | *none* | 🔴 **Unbacked** — TODO-01, D-05 |
| F-07.5 Manual override | *none* | 🔴 **Unbacked** — TODO-04, D-06 |
| F-11.4 Excel/PDF export | *none* | ⚠️ TDD-only — TODO-16 |
| F-08.3 Display idle/privacy state | NFR-SEC-01 (derived) | ⚠️ Derived, not stated — confirm |
| F-13.3 WCAG 2.1 AA | NFR-USA-01 (derived) | ⚠️ Derived, not stated — confirm |
| `visitors.id_document_ref` | *none* | 🔴 **Unbacked** — TODO-13 |
| `visitor_status.no_show` | *none* | 🔴 **Unbacked** — D-07 |
| `notify_channel.in_app` | *none* | 🔴 **Unbacked** — D-07 |
| Edge gateway gate verification | *contradicts SRS §1.2* | 🔴 **Out of scope** — D-08, TODO-19 |

---

## PR-time traceability contract

Every pull request must:

1. Reference its user story in the title: `feat(visitor): submit entry request (US-04.1.1)`
2. Link the story issue, which links its feature, which links its epic, which names its requirement.
3. Update the **Task / Code / Test / PR** columns of the delivery log below.
4. Update documentation (project rule).

A PR that cannot name a requirement does not merge.

## Delivery log

*Populated as work completes. One row per merged user story.*

| Story | Requirement | Tasks | Key modules | Tests | PR | Merged | Release |
|---|---|---|---|---|---|---|---|
| — | — | — | — | — | — | — | — |

---

## Coverage dashboard

| Metric | Count | % |
|---|---|---|
| SRS FRs traced to an epic | 28 / 28 | **100%** |
| SRS FRs traced to a feature | 28 / 28 | **100%** |
| SRS FRs with user stories written | 9 / 28 | 32% |
| SRS FRs ready to implement | 9 / 28 | 32% |
| SRS FRs blocked by an open question | 15 / 28 | 54% |
| SRS FRs implemented | 0 / 28 | 0% |
| NFRs with a testable acceptance criterion | 5 / 8 | 63% |
| Unbacked artefacts identified | 11 | — |

**Read this honestly:** requirement *coverage* is complete — nothing has been missed. Requirement
*readiness* is 32%, because 54% of the SRS depends on the ACS API contract (TODO-02) or on the
missing SRS v2 (TODO-01). This is a supplier/client dependency, not a planning gap.
