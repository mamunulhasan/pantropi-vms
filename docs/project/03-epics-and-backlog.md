# Epic & Backlog Decomposition — Baseline B1

**Derived from:** [01-requirements-catalogue.md](01-requirements-catalogue.md) (28 SRS FRs)
**Traceability:** Requirement → **Epic → Feature → User Story** → Task → Code → Test → PR → Release

---

## Decomposition policy

1. **Every epic traces to at least one SRS requirement.** Two enabler epics (EPIC-01, EPIC-14) have
   no SRS FR; they are marked `enabler` and carry explicit justification.
2. **Each SRS FR belongs to exactly one epic.** No FR is split across epics; no FR is orphaned.
   Coverage is 28/28.
3. **Epics decompose to user stories only when unblocked.** Decomposing a blocked epic invites
   invention. Blocked epics stop at feature level until their gating TODO resolves.
4. **A user story is `INVEST`-shaped** and independently deployable behind a feature flag.

---

## Epic register

| Epic | Title | Requirements | Milestone | Status |
|---|---|---|---|---|
| EPIC-01 | Platform Foundation & Delivery Pipeline | *enabler* | M1 | Ready |
| EPIC-02 | Identity, Authentication & RBAC | FR-ADM-01, FR-ADM-02 | M1 | Ready — TODO-08, TODO-15 |
| EPIC-03 | Configuration & Master Data | *none — see D-05* | M1 | 🔴 **Blocked** — TODO-01 |
| EPIC-04 | Visitor Request & Approval Workflow | FR-VMS-01, 02, 03 | M2 | Ready |
| EPIC-05 | Arrival Verification & Credential Issuance | FR-VMS-04, 05, 06, 07 | M3 | 🔴 **Blocked** — TODO-02 |
| EPIC-06 | Walk-in & Express Entry | FR-VMS-08, 09, 10 | M4 | 🔴 **Blocked** — TODO-02, TODO-03 |
| EPIC-07 | Credential Validity & Lifecycle | FR-VMS-11, 12, 13, 14 | M3 | 🔴 **Blocked** — TODO-02, TODO-09 |
| EPIC-08 | Visitor-Facing Reception Display | FR-VMS-15 | M4 | Ready — TODO-17 |
| EPIC-09 | Card Accountability & Reconciliation | FR-CRD-01, 02, 03 | M4 | 🔴 **Blocked** — TODO-02, TODO-11 |
| EPIC-10 | Notifications & Alerts | FR-NOT-01, 02 | M5 | Ready — TODO-05, TODO-10 |
| EPIC-11 | Reporting & Analytics | FR-REP-01, 02 | M5 | Ready — TODO-16 |
| EPIC-12 | ACS Integration Client & Reliability | FR-API-01, 02, 03 | M3 | 🟠 Partial — TODO-02 |
| EPIC-13 | Portal Branding & Design System | FR-ADM-03, CON-03 | M2 | Ready |
| EPIC-14 | Audit, Security & Observability | *enabler* + NFR-SEC-01 | M1 | 🟠 Partial — TODO-01 |

**Coverage check:** 3+4+3+4+1+3+2+2+3+2+1 = **28 SRS FRs**, each in exactly one epic. ✅

---

## EPIC-01 — Platform Foundation & Delivery Pipeline `enabler`

**Justification for an enabler epic (no SRS FR):** NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 and TDD §3/§9
cannot be satisfied without a build, test, and deployment substrate. This epic creates no
user-visible behaviour and ships no business logic.

| Feature | Description | Traces to |
|---|---|---|
| F-01.1 | Repository, branching model, Conventional Commits enforcement, PR gates | Rule set |
| F-01.2 | Clean Architecture module skeleton (domain / application / infrastructure / api) | TDD §4, NFR-MNT-01 |
| F-01.3 | Local development environment — Docker Compose (PostgreSQL, Redis, Kafka) | TDD §3 |
| F-01.4 | CI pipeline — build, unit test, integration test, coverage gate, SAST | Rule: every change has tests |
| F-01.5 | Database migration tooling, baselined on `vms_schema_postgresql.sql` | Schema |
| F-01.6 | Containerisation + environment configuration strategy | TDD §9.3 |
| F-01.7 | Data retention & purge policy implementation | NFR-CMP-01 · 🔴 TODO-12 |

### Ready user stories

| Story | As a… | I want… | So that… | Traces to |
|---|---|---|---|---|
| US-01.1.1 | developer | a repository with branch protection on `main` and `develop` | no unreviewed code reaches a shared branch | F-01.1 |
| US-01.1.2 | developer | commit messages validated against Conventional Commits | release notes and traceability are derivable from history | F-01.1 |
| US-01.2.1 | architect | a Clean Architecture module skeleton with dependency rules enforced at build time | ACS specifics cannot leak into domain logic | F-01.2, NFR-MNT-01 |
| US-01.3.1 | developer | `docker compose up` to give me PostgreSQL, Redis and Kafka | I can run the system locally without shared infrastructure | F-01.3 |
| US-01.4.1 | developer | CI that fails the build on test failure or coverage regression | untested code cannot merge | F-01.4 |
| US-01.5.1 | developer | versioned, repeatable database migrations | every environment reaches an identical schema state | F-01.5 |

---

## EPIC-02 — Identity, Authentication & RBAC

**Requirements:** FR-ADM-01, FR-ADM-02 · **Supports:** NFR-SEC-01
**Open:** TODO-08 (what "120 logins" means), TODO-15 (local accounts vs SSO), TODO-14 (tenant isolation)

| Feature | Description | Traces to |
|---|---|---|
| F-02.1 | User authentication — login, logout, session lifecycle | FR-ADM-02, TDD §7 |
| F-02.2 | Role & permission model with enforcement at the API boundary | NFR-SEC-01, Schema §4 |
| F-02.3 | Master Admin role with approval + credential-request authority | FR-ADM-01 |
| F-02.4 | Reception user provisioning at 120 + 1 scale | FR-ADM-02 · 🟠 TODO-08 |
| F-02.5 | Tenant / floor data scoping | 🟠 TODO-14 — *cannot design without an answer* |

### Ready user stories

| Story | As a… | I want… | So that… | Traces to |
|---|---|---|---|---|
| US-02.1.1 | VMS user | to log in with my credentials and receive a short-lived session token | I can access the portal securely | FR-ADM-02 |
| US-02.1.2 | VMS user | my session to expire after inactivity and refresh transparently while I work | an unattended reception PC is not a standing door into the system | NFR-SEC-01 |
| US-02.2.1 | system administrator | to assign a role to a user | access rights follow the person's job, not ad-hoc grants | NFR-SEC-01 |
| US-02.2.2 | security reviewer | every API endpoint to deny by default unless a permission grants it | a missing annotation fails closed, not open | NFR-SEC-01, OWASP A01 |
| US-02.3.1 | Master Admin | authority to approve visitor access and initiate credential requests | central reception can act as the single point of approval | FR-ADM-01 |

> **Sprint-planning note.** US-02.4.x and US-02.5.x are deliberately not written. Writing them now
> means choosing an answer to TODO-08 and TODO-14 ourselves.

---

## EPIC-03 — Configuration & Master Data 🔴 BLOCKED

**Requirements:** **None in the attached SRS.** · **Blocked by:** TODO-01 · **See:** D-03, D-05

The schema defines seven master-data tables and the TDD defines a Configuration & Master Data
Service against `FR-CFG-01..08` — requirements we do not have. Building this epic means inventing
the rules for Building, Floor, Tenant, Reception, Visitor Type, Pass Type and Holiday Calendar.

| Feature | Description | Traces to |
|---|---|---|
| F-03.1 | Building & Floor master data | `FR-CFG-02/03` *(undefined)* |
| F-03.2 | Tenant & Host master data | `FR-CFG-04` *(undefined)* |
| F-03.3 | Reception point master data | `FR-CFG-05` *(undefined)* |
| F-03.4 | Visitor Type & Pass Type master data | `FR-CFG-06/07` *(undefined)* |
| F-03.5 | Holiday calendar | `FR-CFG-08` *(undefined)* |
| F-03.6 | System settings | `FR-SET-01` *(undefined)* |

**No user stories will be written for this epic until TODO-01 is resolved.**

---

## EPIC-04 — Visitor Request & Approval Workflow

**Requirements:** FR-VMS-01, FR-VMS-02, FR-VMS-03 · **Status:** Ready
*Depends on EPIC-02 (identity) and, for tenant/host references, EPIC-03.*

| Feature | Description | Traces to |
|---|---|---|
| F-04.1 | Tenant portal — submit a visitor entry request | FR-VMS-01 |
| F-04.2 | FM Admin dashboard — review, approve, reject | FR-VMS-02 |
| F-04.3 | Floor receptionist pre-registration to central server | FR-VMS-03 |
| F-04.4 | Visitor request state machine + domain events | TDD §4.2 |

### Ready user stories

| Story | As a… | I want… | So that… | Traces to |
|---|---|---|---|---|
| US-04.1.1 | tenant | to log into the portal and submit a visitor entry request with visitor details and a requested time window | my guest is expected when they arrive | FR-VMS-01 |
| US-04.1.2 | tenant | to add multiple visitors to a single request | I do not re-key a shared time window for a group | FR-VMS-01 |
| US-04.1.3 | tenant | to see the status of my submitted requests | I know whether my guest is cleared to arrive | FR-VMS-01 |
| US-04.2.1 | FM Admin | a dashboard of pending visitor requests | I can work a queue rather than hunt for submissions | FR-VMS-02 |
| US-04.2.2 | FM Admin | to approve a visitor request | the visitor becomes eligible for credential issuance on arrival | FR-VMS-02 |
| US-04.2.3 | FM Admin | to reject a request with a recorded reason | the tenant understands the decision and we retain the rationale | FR-VMS-02 |
| US-04.3.1 | floor receptionist | to pre-register visitor details and submit them to the central server | central reception can verify the visitor on arrival | FR-VMS-03 |
| US-04.4.1 | system | to emit a domain event when a request is approved | downstream services react without coupling to the approval service | TDD §4.2 |

---

## EPIC-05 — Arrival Verification & Credential Issuance 🔴 BLOCKED

**Requirements:** FR-VMS-04, 05, 06, 07 · **Blocked by:** TODO-02 (ACS API contract)

| Feature | Description | Traces to |
|---|---|---|
| F-05.1 | Arrival verification — look up the pre-registered visitor and confirm appointment | FR-VMS-04 |
| F-05.2 | Credential creation request to ACS | FR-VMS-05 |
| F-05.3 | Store and display the ACS-returned credential reference | FR-VMS-06 |
| F-05.4 | Share credential by email | FR-VMS-07 |
| F-05.5 | Share credential by on-screen display | FR-VMS-07 → EPIC-08 |
| F-05.6 | Share credential by printed copy | FR-VMS-07 |

**Partial start permitted:** F-05.1 depends only on VMS-owned data and may begin once EPIC-04 lands.
F-05.2/5.3 may be built against the ACS simulator (EPIC-12) but cannot close.

---

## EPIC-06 — Walk-in & Express Entry 🔴 BLOCKED

**Requirements:** FR-VMS-08, 09, 10 · **Blocked by:** TODO-02, TODO-03, TODO-18

| Feature | Description | Traces to |
|---|---|---|
| F-06.1 | Walk-in registration at reception | FR-VMS-08 |
| F-06.2 | Host / FM Admin approval confirmation for a walk-in | FR-VMS-08 · 🟠 TODO-18 |
| F-06.3 | Walk-in credential request via the same ACS path | FR-VMS-09 |
| F-06.4 | Express entry — pre-scheduled visitor bypasses reception | FR-VMS-10 · 🔴 TODO-03 |

> **F-06.4 may be descoped entirely.** The SRS itself flags that the vendor's current submission
> describes a fully reception-mediated flow. Do not plan capacity against it until TODO-03 resolves.

---

## EPIC-07 — Credential Validity & Lifecycle 🔴 BLOCKED

**Requirements:** FR-VMS-11, 12, 13, 14 · **Blocked by:** TODO-02, TODO-09 · **Related:** TODO-04

| Feature | Description | Traces to |
|---|---|---|
| F-07.1 | Validity window specification on credential creation | FR-VMS-11 |
| F-07.2 | Credential deactivation at end of validity window | FR-VMS-12 · 🔴 TODO-09 |
| F-07.3 | Time-bound vs one-time-use restriction selection | FR-VMS-13 |
| F-07.4 | On-demand credential status query, Redis-cached | FR-VMS-14, TDD §6.1 |
| F-07.5 | Manual override | *no SRS requirement* · 🔴 TODO-04 — **do not build** |

---

## EPIC-08 — Visitor-Facing Reception Display

**Requirements:** FR-VMS-15 · **Status:** Ready pending TODO-17

| Feature | Description | Traces to |
|---|---|---|
| F-08.1 | Slave display surface showing visitor QR code and current status | FR-VMS-15 |
| F-08.2 | Pairing a display to a reception workstation | 🟡 TODO-17 |
| F-08.3 | Idle / privacy state — clear visitor PII when no transaction is active | NFR-SEC-01 *(derived; confirm)* |

---

## EPIC-09 — Card Accountability & Reconciliation 🔴 BLOCKED

**Requirements:** FR-CRD-01, 02, 03 · **Blocked by:** TODO-02, TODO-11

| Feature | Description | Traces to |
|---|---|---|
| F-09.1 | Log RFID card issuance with the ACS-returned card identifier | FR-CRD-01 |
| F-09.2 | Log card return from ACS-reported card-issuer events | FR-CRD-02 |
| F-09.3 | Daily issued-vs-returned reconciliation job | FR-CRD-03 · 🟠 TODO-11 |
| F-09.4 | Discrepancy flagging for missing cards | FR-CRD-03 · 🟠 TODO-11 |

---

## EPIC-10 — Notifications & Alerts

**Requirements:** FR-NOT-01, FR-NOT-02 · **Status:** Ready for email; WhatsApp gated on TODO-05

| Feature | Description | Traces to |
|---|---|---|
| F-10.1 | Notification service — event subscription and dispatch | TDD §8 |
| F-10.2 | Email channel + delivery status logging | FR-NOT-01, SRS §4.4 |
| F-10.3 | Credential confirmation to host and visitor, with QR and welcome message | FR-NOT-01 |
| F-10.4 | Exit alert to host and visitor on ACS exit event | FR-NOT-02 |
| F-10.5 | WhatsApp channel | FR-NOT-01 · 🟠 TODO-05 — build behind a disabled flag |
| F-10.6 | Channel selection rule | 🟠 TODO-10 |

### Ready user stories (email path only)

| Story | As a… | I want… | So that… | Traces to |
|---|---|---|---|---|
| US-10.2.1 | system | to dispatch email through a configured gateway and record delivery status | failures are visible rather than silent | FR-NOT-01, SRS §6 |
| US-10.3.1 | visitor | to receive a confirmation email with my QR code and a welcome message once my credential is issued | I can enter without queuing at reception | FR-NOT-01 |
| US-10.3.2 | host | to be notified when my visitor's credential has been issued | I know my guest is cleared and on their way | FR-NOT-01 |
| US-10.4.1 | host | to be alerted when my visitor exits the building | I know the visit has ended | FR-NOT-02 |

---

## EPIC-11 — Reporting & Analytics

**Requirements:** FR-REP-01, FR-REP-02 · **Status:** Ready; export formats gated on TODO-16

| Feature | Description | Traces to |
|---|---|---|
| F-11.1 | Daily / weekly / monthly visitor activity report generation | FR-REP-01 |
| F-11.2 | End-of-day credentials-and-cards issued/returned report | FR-REP-02 |
| F-11.3 | Report scheduling and automatic generation | FR-REP-01 (*"automatically"*) |
| F-11.4 | Excel / PDF export | 🟡 TODO-16 — TDD-only |

---

## EPIC-12 — ACS Integration Client & Reliability 🟠 PARTIAL

**Requirements:** FR-API-01, 02, 03 · **Supports:** NFR-REL-01, NFR-MNT-01, CON-01, CON-02

This is the single most design-sensitive component (TDD §6) and the one most affected by TODO-02.
**The interface can and should be built now**; only the wire-level adapter is blocked.

| Feature | Description | Traces to |
|---|---|---|
| F-12.1 | ACS client port — the internal interface all VMS services depend on | NFR-MNT-01, CON-02, TDD §6 |
| F-12.2 | **ACS simulator** for development and integration testing | 🔴 TODO-02 mitigation |
| F-12.3 | Outbound queue with exponential backoff and retry | FR-API-01 |
| F-12.4 | Dead-letter handling + receptionist/admin notification on failure | FR-API-01 |
| F-12.5 | Full request/response audit logging | FR-API-02 |
| F-12.6 | Service authentication to ACS, secrets held outside source and database | FR-API-03, TDD §6.3 |
| F-12.7 | Idempotent inbound event handling keyed on ACS event id | TDD §6.2, Schema `access_events.acs_event_id` |
| F-12.8 | Periodic VMS↔ACS state synchronisation job | TDD §6.2 |
| F-12.9 | Wire-level ACS adapter | 🔴 **TODO-02 — cannot start** |

### Ready user stories

| Story | As a… | I want… | So that… | Traces to |
|---|---|---|---|---|
| US-12.1.1 | architect | a single ACS port that no domain service bypasses | an ACS contract change touches one module | NFR-MNT-01, CON-02 |
| US-12.2.1 | developer | an ACS simulator implementing our port with configurable latency and failure modes | integration tests run without the real ACS | TODO-02 mitigation |
| US-12.3.1 | receptionist | a credential request to be queued and retried when ACS is unreachable | a network blip does not lose the visitor's issuance | FR-API-01 |
| US-12.3.2 | receptionist | to be told immediately when issuance is queued rather than completed | I can tell the visitor what is happening | FR-API-01 |
| US-12.5.1 | auditor | every ACS request and response logged with correlation ids | integration failures can be reconstructed | FR-API-02 |

---

## EPIC-13 — Portal Branding & Design System

**Requirements:** FR-ADM-03, CON-03 · **Status:** Ready pending brand assets

| Feature | Description | Traces to |
|---|---|---|
| F-13.1 | Design tokens — logo, colour palette, typography, theme | FR-ADM-03 |
| F-13.2 | Shared component library across tenant, admin, reception, display views | TDD §3 |
| F-13.3 | Accessibility baseline (WCAG 2.1 AA) | NFR-USA-01 *(derived; confirm)* |

**Dependency:** client brand assets and guidelines have not been supplied. Not blocking — build
against a neutral token set and swap.

---

## EPIC-14 — Audit, Security & Observability 🟠 PARTIAL `enabler`

**Requirements:** NFR-SEC-01 (direct); `FR-AUD-01/02` referenced but undefined — see D-03
**Blocked in part by:** TODO-01

**Justification for an enabler epic:** NFR-SEC-01 and FR-API-02 require access control and logging
that must be built once, centrally. The *audit log* specifically is TDD/schema-only.

| Feature | Description | Traces to |
|---|---|---|
| F-14.1 | Structured logging + correlation ids across services | TDD §10 |
| F-14.2 | Append-only audit log | `FR-AUD-01` *(undefined)* · 🔴 TODO-01 |
| F-14.3 | Transport and at-rest encryption for visitor personal data | TDD §7, NFR-CMP-01 |
| F-14.4 | Secrets management | FR-API-03, TDD §6.3, §7 |
| F-14.5 | OWASP ASVS baseline verification in CI | Project rule: OWASP practices |
| F-14.6 | Health checks, metrics, alerting | NFR-AVL-01, TDD §10 |

---

## Backlog readiness summary

| State | Epics | Meaning |
|---|---|---|
| **Ready to sprint** | EPIC-01, 02, 04, 13 + partial 08, 10, 11, 12, 14 | Requirements clear; stories written |
| 🟠 **Partial** | EPIC-12, EPIC-14 | Interface/enabler work can start; adapter or audit gated |
| 🔴 **Blocked** | EPIC-03, 05, 06, 07, 09 | Would require inventing requirements |

**33 user stories** are written and ready. They are concentrated in foundation, identity, the
visitor request/approval workflow, branding, the ACS port, and the email notification path — i.e.
everything that does *not* depend on the missing ACS contract or the missing SRS v2.
