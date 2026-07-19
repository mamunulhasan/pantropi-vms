# Epic & Feature Index — Complete Backlog Skeleton

**Baseline:** B1 · **Sources:** SRS (authoritative for requirements) + TDD (design input, now authorized as a backlog source)
**Structure:** 4 Phases → 19 Epics → 93 Features → User Stories → Development Tasks

This file is the **contract**. Every phase backlog file decomposes exactly the epics and features
listed here — no additions, no renames, no renumbering.

---

## Identifier scheme

| Level | Format | Example |
|---|---|---|
| Phase / Milestone | `Phase N` | Phase 2 |
| Epic | `EPIC-NN` | EPIC-07 |
| Feature | `F-<epic>.<n>` | F-07.3 |
| User Story | `US-<epic>.<feature>.<n>` | US-07.3.1 |
| Task | `T-<epic>.<feature>.<story>.<n>` | T-07.3.1.2 |

Requirement references are **always qualified**: `FR-VMS-01 (SRS B1)` — see discrepancy D-02.

## Provenance markers

Applied to every epic, feature, story and task.

| Marker | Meaning | Implementable? |
|---|---|---|
| `SRS` | Backed by a numbered SRS functional requirement | ✅ Yes |
| `SRS-NFR` / `SRS-CON` | Backed by an SRS non-functional requirement or constraint | ✅ Yes |
| `TDD-DERIVED` | From the TDD, with **no** SRS requirement behind it | ⚠️ Backlog only — needs TODO-01 disposition |
| `ENABLER` | Technical necessity, no direct requirement; justified against an NFR | ✅ Yes |
| `BLOCKED` | Gated on an open question — see `../07-open-questions.md` | ⛔ No |

> **The rule still holds.** `TDD-DERIVED` items are planned, estimated and sequenced so the project
> has a realistic shape and cost — but they are not authorized for implementation until TODO-01 is
> dispositioned. A `TDD-DERIVED` story entering a sprint without that disposition is a process
> failure, and the sprint-review reverse trace exists to catch it.

## Priority scale

| Priority | Meaning |
|---|---|
| `P0` | Critical — blocks the phase or the critical path |
| `P1` | High — core requirement, needed for phase acceptance |
| `P2` | Medium — required, but the phase can demo without it |
| `P3` | Low — deferrable to a later release |

## Estimation scale

Fibonacci story points: `1, 2, 3, 5, 8`. **13 is not a valid estimate** — anything that large must be
split before it enters the backlog, not merely before sprint entry. The phase backlogs enforce this.

| Points | Rough shape |
|---|---|
| 1 | Trivial, well-understood, < half a day |
| 2 | Small, single component |
| 3 | Standard story, one component, some edge cases |
| 5 | Multi-component or non-trivial logic |
| 8 | Large, cross-cutting, or carries real unknowns |


---

## Phase 1 — Platform Foundation

**Milestone goal:** a deployable, secured, observable, empty application with master data and
identity in place. No visitor workflow.
**TDD alignment:** §4.1 Configuration & Master Data Service, §4.6 cross-cutting services.

### EPIC-01 — Engineering Platform & Delivery Pipeline `ENABLER`
*Justified by NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 (SRS B1); TDD §3, §9.*

| Feature | Title | Provenance |
|---|---|---|
| F-01.1 | Repository, branching & commit governance | `ENABLER` |
| F-01.2 | Clean Architecture module skeleton & boundary enforcement | `ENABLER` / NFR-MNT-01 |
| F-01.3 | Local development environment (PostgreSQL, Redis, Kafka) | `ENABLER` |
| F-01.4 | CI pipeline — build, test, coverage, SAST | `ENABLER` |
| F-01.5 | Database migration framework & schema baseline | `ENABLER` |
| F-01.6 | Containerisation & per-environment configuration | `ENABLER` / TDD §9.3 |
| F-01.7 | Observability foundation — logging, metrics, health, correlation ids | `ENABLER` / TDD §10 |

### EPIC-02 — Identity, Authentication & Session Management
| Feature | Title | Provenance |
|---|---|---|
| F-02.1 | User authentication & JWT session lifecycle | `SRS` FR-ADM-02 |
| F-02.2 | User provisioning & administration at 120+1 scale | `SRS` FR-ADM-02 · TODO-08 |
| F-02.3 | Account security policy — password, lockout, rotation | `SRS-NFR` NFR-SEC-01 |
| F-02.4 | Master Admin authority | `SRS` FR-ADM-01 |

### EPIC-03 — Authorization & RBAC
| Feature | Title | Provenance |
|---|---|---|
| F-03.1 | Role & permission model | `TDD-DERIVED` FR-USR-02 |
| F-03.2 | API-boundary authorization enforcement | `SRS-NFR` NFR-SEC-01 |
| F-03.3 | Role & permission administration | `TDD-DERIVED` FR-USR-02 |
| F-03.4 | Tenant & floor data scoping | `BLOCKED` TODO-14 |

### EPIC-04 — Configuration & Master Data `TDD-DERIVED`
*Entire epic is TDD/schema-derived. Held pending TODO-01.*

| Feature | Title | Provenance |
|---|---|---|
| F-04.1 | Building master data | `TDD-DERIVED` FR-CFG-02 |
| F-04.2 | Floor master data | `TDD-DERIVED` FR-CFG-03 |
| F-04.3 | Tenant master data | `TDD-DERIVED` FR-CFG-04 |
| F-04.4 | Reception point master data | `TDD-DERIVED` FR-CFG-05 |
| F-04.5 | Visitor type master data | `TDD-DERIVED` FR-CFG-06 |
| F-04.6 | Pass type master data | `TDD-DERIVED` FR-CFG-07 |
| F-04.7 | Holiday calendar | `TDD-DERIVED` FR-CFG-08 |
| F-04.8 | System settings | `TDD-DERIVED` FR-SET-01 |

> ⚠️ **`FR-CFG-01` is unallocated.** The requirements catalogue §5 lists `FR-CFG-01 … FR-CFG-08`,
> but only FR-CFG-02..08 have an identifiable subject in the schema. FR-CFG-01's content is unknown
> — it maps to no feature and cannot, until TODO-01 supplies its definition.
| F-04.9 | Master data caching & invalidation | `TDD-DERIVED` TDD §4.1 |

### EPIC-05 — Audit, Security & Compliance Foundation
| Feature | Title | Provenance |
|---|---|---|
| F-05.1 | Append-only audit log | `TDD-DERIVED` FR-AUD-01 |
| F-05.2 | Secrets management | `SRS` FR-API-03 · TDD §6.3 |
| F-05.3 | Data protection — encryption in transit and at rest | `SRS-NFR` NFR-CMP-01 |
| F-05.4 | Data retention & purge | `BLOCKED` TODO-12 |
| F-05.5 | OWASP ASVS baseline verification in CI | `ENABLER` / OWASP |

### EPIC-06 — Portal Shell & Design System
| Feature | Title | Provenance |
|---|---|---|
| F-06.1 | Design tokens & client branding | `SRS` FR-ADM-03, CON-03 |
| F-06.2 | Shared component library | `TDD-DERIVED` TDD §3 |
| F-06.3 | Role-driven routing & navigation shell | `TDD-DERIVED` TDD §3 |
| F-06.4 | Accessibility baseline (WCAG 2.1 AA) | `SRS-NFR` NFR-USA-01 *(derived)* |

---

## Phase 2 — Visitor Registration & Pass Generation

**Milestone goal:** the complete pre-arrival journey and pass generation. **Client-demonstrable.**
**TDD alignment:** §4.2 Visitor & Approval Service, §4.3 Pass & Credential Service.

### EPIC-07 — Visitor Request & Approval
| Feature | Title | Provenance |
|---|---|---|
| F-07.1 | Tenant visitor request submission | `SRS` FR-VMS-01 |
| F-07.2 | Group & multi-visitor requests | `SRS` FR-VMS-01 |
| F-07.3 | FM Admin approval dashboard | `SRS` FR-VMS-02 |
| F-07.4 | Approve / reject with recorded reason | `SRS` FR-VMS-02 |
| F-07.5 | Request state machine & domain events | `TDD-DERIVED` TDD §4.2 |
| F-07.6 | Request status visibility for tenants | `SRS` FR-VMS-01 |

### EPIC-08 — Visitor Pre-Registration
| Feature | Title | Provenance |
|---|---|---|
| F-08.1 | Floor receptionist pre-registration | `SRS` FR-VMS-03 |
| F-08.2 | Central server submission & synchronisation | `SRS` FR-VMS-03 |
| F-08.3 | Appointment scheduling & validity windows | `SRS` FR-VMS-11 |
| F-08.4 | Visitor record lifecycle & status tracking | `TDD-DERIVED` FR-ENT-10 |

### EPIC-09 — Pass & Credential Generation
| Feature | Title | Provenance |
|---|---|---|
| F-09.1 | Credential creation request orchestration | `SRS` FR-VMS-05 |
| F-09.2 | Credential reference storage | `SRS` FR-VMS-06 |
| F-09.3 | Validity window specification | `SRS` FR-VMS-11 |
| F-09.4 | Restriction type selection — time-bound / one-time | `SRS` FR-VMS-13 |
| F-09.5 | QR rendering & pass presentation | `SRS` FR-VMS-07 |
| F-09.6 | Pass delivery — email, on-screen, print | `SRS` FR-VMS-07 |
| F-09.7 | Credential lifecycle state machine | `TDD-DERIVED` TDD §4.3 |

### EPIC-10 — Host Management
| Feature | Title | Provenance |
|---|---|---|
| F-10.1 | Host directory | `TDD-DERIVED` FR-VMS-06 (TDD §4.2) |
| F-10.2 | Host assignment to visitor requests | `TDD-DERIVED` TDD §4.2 |

---

## Phase 3 — Entry Verification & ACS Integration

**Milestone goal:** the live visit lifecycle and the ACS integration boundary.
**TDD alignment:** §4.4 Entry & ACS Operations Service, §6 ACS integration design.
**⚠️ Gated on TODO-02** — the UAL ACS API contract. See ADR-0002 for the simulator strategy.

### EPIC-11 — ACS Integration Client (Anti-Corruption Layer)
| Feature | Title | Provenance |
|---|---|---|
| F-11.1 | ACS port definition | `SRS-CON` CON-02, NFR-MNT-01 |
| F-11.2 | ACS simulator | `ENABLER` / ADR-0002 |
| F-11.3 | Outbound retry, backoff & durable outbox | `SRS` FR-API-01 |
| F-11.4 | Dead-letter handling & operator alerting | `SRS` FR-API-01 |
| F-11.5 | API request/response audit logging | `SRS` FR-API-02 |
| F-11.6 | ACS service authentication | `SRS` FR-API-03 |
| F-11.7 | Wire-level ACS adapter | `BLOCKED` TODO-02 |
| F-11.8 | Inbound event consumption & idempotency | `TDD-DERIVED` TDD §6.2 |
| F-11.9 | Periodic VMS↔ACS state synchronisation | `TDD-DERIVED` FR-ENT-08 |

### EPIC-12 — Arrival Verification & Entry
| Feature | Title | Provenance |
|---|---|---|
| F-12.1 | Arrival lookup & appointment confirmation | `SRS` FR-VMS-04 |
| F-12.2 | Check-in / check-out status tracking | `TDD-DERIVED` FR-ENT-10 |
| F-12.3 | Access & exit event processing | `TDD-DERIVED` FR-ENT-06/07 |
| F-12.4 | Visitor-facing reception display | `SRS` FR-VMS-15 · TODO-17 |

### EPIC-13 — Walk-in & Express Entry
| Feature | Title | Provenance |
|---|---|---|
| F-13.1 | Walk-in visitor registration | `SRS` FR-VMS-08 |
| F-13.2 | Host / FM Admin approval confirmation | `SRS` FR-VMS-08 · TODO-18 |
| F-13.3 | Walk-in credential request | `SRS` FR-VMS-09 |
| F-13.4 | Express entry — barrier scan bypassing reception | `BLOCKED` FR-VMS-10 · TODO-03 |

### EPIC-14 — Credential Lifecycle Operations
| Feature | Title | Provenance |
|---|---|---|
| F-14.1 | Credential deactivation at end of validity | `SRS` FR-VMS-12 · TODO-09 |
| F-14.2 | Credential status query & caching | `SRS` FR-VMS-14 |
| F-14.3 | Credential cancellation & revocation | `TDD-DERIVED` TDD §4.3 |
| F-14.4 | Manual override | `BLOCKED` TODO-04 — **do not build** |

### EPIC-15 — Card Accountability & Reconciliation
| Feature | Title | Provenance |
|---|---|---|
| F-15.1 | RFID card issuance logging | `SRS` FR-CRD-01 |
| F-15.2 | Card return logging | `SRS` FR-CRD-02 |
| F-15.3 | Daily issued-vs-returned reconciliation | `SRS` FR-CRD-03 · TODO-11 |
| F-15.4 | Discrepancy flagging & missing-card alerts | `SRS` FR-CRD-03 |

---

## Phase 4 — Reporting & Notification

**Milestone goal:** the notification platform and the reporting suite.
**TDD alignment:** §4.5 Reporting & Analytics Service, §8 notification design.

### EPIC-16 — Notification Platform
| Feature | Title | Provenance |
|---|---|---|
| F-16.1 | Notification service & domain event subscription | `TDD-DERIVED` TDD §8 |
| F-16.2 | Email channel & delivery status logging | `SRS` FR-NOT-01 |
| F-16.3 | WhatsApp channel | `BLOCKED` FR-NOT-01 · TODO-05 |
| F-16.4 | Notification templates & localisation | `TDD-DERIVED` TDD §8 |
| F-16.5 | Channel selection & recipient preferences | `BLOCKED` TODO-10 |

### EPIC-17 — Notification Scenarios
| Feature | Title | Provenance |
|---|---|---|
| F-17.1 | Credential confirmation to visitor & host | `SRS` FR-NOT-01 |
| F-17.2 | Exit alerts to host & visitor | `SRS` FR-NOT-02 |
| F-17.3 | Host notification — approved / in / out | `TDD-DERIVED` FR-NOT-03 |
| F-17.4 | Appointment reminders | `TDD-DERIVED` FR-NOT-04 |
| F-17.5 | System alerts to administrators | `TDD-DERIVED` FR-NOT-05 |

### EPIC-18 — Reporting Suite
| Feature | Title | Provenance |
|---|---|---|
| F-18.1 | Daily / weekly / monthly visitor activity reports | `SRS` FR-REP-01 |
| F-18.2 | End-of-day credentials & cards report | `SRS` FR-REP-02 |
| F-18.3 | Report scheduling & automatic generation | `SRS` FR-REP-01 |
| F-18.4 | Audit report | `TDD-DERIVED` FR-REP-06 |

### EPIC-19 — Analytics & Export
| Feature | Title | Provenance |
|---|---|---|
| F-19.1 | Analytics dashboard | `TDD-DERIVED` FR-ANL-01 |
| F-19.2 | Excel & PDF export | `TDD-DERIVED` FR-EXP-01 · TODO-16 |

---

## SRS requirement coverage check

Every one of the 28 SRS functional requirements maps into this skeleton:

| Requirement | Phase | Epic.Feature |
|---|---|---|
| FR-VMS-01 | 2 | F-07.1, F-07.2, F-07.6 |
| FR-VMS-02 | 2 | F-07.3, F-07.4 |
| FR-VMS-03 | 2 | F-08.1, F-08.2 |
| FR-VMS-04 | 3 | F-12.1 |
| FR-VMS-05 | 2 | F-09.1 |
| FR-VMS-06 | 2 | F-09.2 |
| FR-VMS-07 | 2 | F-09.5, F-09.6 |
| FR-VMS-08 | 3 | F-13.1, F-13.2 |
| FR-VMS-09 | 3 | F-13.3 |
| FR-VMS-10 | 3 | F-13.4 ⛔ |
| FR-VMS-11 | 2 | F-08.3, F-09.3 |
| FR-VMS-12 | 3 | F-14.1 |
| FR-VMS-13 | 2 | F-09.4 |
| FR-VMS-14 | 3 | F-14.2 |
| FR-VMS-15 | 3 | F-12.4 |
| FR-CRD-01 | 3 | F-15.1 |
| FR-CRD-02 | 3 | F-15.2 |
| FR-CRD-03 | 3 | F-15.3, F-15.4 |
| FR-NOT-01 | 4 | F-16.2, F-16.3, F-17.1 |
| FR-NOT-02 | 4 | F-17.2 |
| FR-REP-01 | 4 | F-18.1, F-18.3 |
| FR-REP-02 | 4 | F-18.2 |
| FR-API-01 | 3 | F-11.3, F-11.4 |
| FR-API-02 | 3 | F-11.5 |
| FR-API-03 | 1 + 3 | F-05.2 (Phase 1, secrets), F-11.6 (Phase 3, ACS auth) |
| FR-ADM-01 | 1 | F-02.4 |
| FR-ADM-02 | 1 | F-02.1, F-02.2 |
| FR-ADM-03 | 1 | F-06.1 |

**Coverage: 28 / 28 (100%).** No SRS requirement is unallocated.

## Cross-phase dependency summary

```
Phase 1 ──────────────► Phase 2 ──────────────► Phase 3 ──────────────► Phase 4
 identity, RBAC,         requests, approval,     ACS client, arrival,    notifications,
 master data,            pre-registration,       walk-in, card           reporting,
 audit, portal shell     pass generation         accountability          analytics

Cross-cutting pull-forwards (deliberate, to de-risk the TODO-02 gate):
  • F-11.1 ACS port + F-11.2 simulator  → start in Phase 1, needed by Phase 2 F-09.1
  • F-16.1/F-16.2 notification + email  → start in Phase 2, needed by F-09.6 pass delivery
```

Phase 2's pass generation cannot complete without the ACS port from Phase 3. Rather than reorder
the phases, **F-11.1 and F-11.2 are pulled forward into Phase 1** as foundation work. This is the
single most important sequencing decision in the plan — see ADR-0002.
