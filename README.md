# Project Pinnacle — Visitor Management System (VMS)

**Client:** CPG Corporation Pte Ltd — Westgate Tower
**Delivered by:** Pantropi Limited
**ACS vendor (integration partner):** Universal Automations Ltd (UAL)

A web-based Visitor Management System covering the visitor lifecycle — request, approval,
pre-registration, arrival verification, and credential lifecycle management.

The Access Control System (flap barriers, RFID/QR readers, physical credential enforcement) is a
**separate external system**. VMS never controls that hardware. It integrates with ACS exclusively
through a documented API, and ACS remains the system of record for physical access enforcement.

---

## 📌 Project status

**Phase:** M0 — Inception & Requirements Baseline
**Application code:** none yet, by design.

Requirements analysis is complete. Two blocking dependencies must be resolved by the client and the
ACS vendor before sustained implementation can begin:

| Blocker | Owner | Impact |
|---|---|---|
| **TODO-01** — SRS v2 is missing | CPG / Pantropi doc control | The TDD is written against a 96-requirement SRS v2; the attached SRS has 28. ~50 referenced requirement IDs are undefined. |
| **TODO-02** — ACS API contract not published | UAL | Blocks all credential operations — 15 of 28 requirements. |
| **TODO-19** — Gate decision authority unclear | CPG / UAL | The TDD's edge gateway contradicts the SRS scope boundary. Affects liability. |

**32% of requirements are ready to implement now.** That work is scheduled into M1 and M2 and does
not depend on the blockers above. See the [milestone plan](docs/project/04-milestones-and-release-plan.md).

---

## 📚 Documentation

### Source of truth
| Document | Description |
|---|---|
| [SRS](docs/VMS_Only_SRS_Project_Pinnacle.docx) | Software Requirements Specification — VMS-only scope |
| [TDD](docs/VMS%20Technical%20Design%20Document.docx) | Technical Design Document |
| [Schema](docs/vms_schema_postgresql.sql) | PostgreSQL 14+ database schema |

### Project governance
| Document | Read it when… |
|---|---|
| [Requirements Catalogue](docs/project/01-requirements-catalogue.md) | You need to know what we may build. **Nothing outside this document gets implemented.** |
| [Traceability Matrix](docs/project/02-traceability-matrix.md) | You need to prove a requirement is covered, or check nothing unbacked is being built |
| [Epics & Backlog](docs/project/03-epics-and-backlog.md) | You are planning a sprint |
| [Milestones & Release Plan](docs/project/04-milestones-and-release-plan.md) | You need the delivery sequence and its dependencies |
| [Workflow & Branching](docs/project/05-workflow-and-branching.md) | You are about to write code |
| [Open Questions](docs/project/07-open-questions.md) | A requirement is ambiguous — check here before guessing |
| [Source Document Discrepancies](docs/project/08-source-document-discrepancies.md) | The three documents disagree and you need to know which wins |
| [ADRs](docs/adr/) | You need the reasoning behind an architectural decision |
| [CONTRIBUTING](CONTRIBUTING.md) | Every time you open a PR |

---

## 🏛 Architecture

Layered, service-oriented, Clean Architecture. Per TDD §3 — recommended stack, open to adjustment:

| Layer | Technology |
|---|---|
| Front end | Next.js |
| Back end | Java + Spring Boot |
| API security | Spring Security, JWT / OAuth2 |
| Database | PostgreSQL |
| Cache | Redis |
| Messaging | Apache Kafka (with dead-letter topic) |
| Deployment | Docker, Kubernetes (cloud) / single-host container runtime (on-premise) |

### The rule that matters most

> **No ACS-specific type may exist outside the ACS integration module.**

This satisfies CON-01, CON-02 and NFR-MNT-01 — and it is the entire mitigation for the missing ACS
API contract. Because ACS specifics are confined to one adapter, the system can be built and tested
against a simulator today, and switched to the real contract by swapping one module rather than
rewriting the application. It is enforced by architecture fitness tests in CI, not by convention.

---

## 🔒 Scope boundary — what VMS does NOT do

Per SRS §1.2. Any issue proposing these is closed as out of scope:

- Flap barrier gate hardware, lane configuration, throughput
- RFID/QR reader hardware, mounting, dual-frequency verification logic
- Power infrastructure (PSU, UPS) for access control hardware
- **Physical grant/deny decisions at the gate** — ACS enforces these using credential data VMS
  provisions via API

---

## 🚀 Getting started

Application scaffolding lands in **M1** (EPIC-01). Until then this repository holds requirements
analysis, project governance, and the GitHub project definition.

To set up the GitHub project (labels, milestones, epic issues):

```bash
# Requires the GitHub CLI: https://cli.github.com
gh auth login
./scripts/bootstrap-github.sh          # add --dry-run to preview
```

---

## 🤝 Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first. In short:

- Feature branches only — never commit directly to `develop` or `main`
- One user story at a time
- Every change has tests; every PR updates documentation
- Conventional Commits, naming the user story id
- Never implement functionality that is not in the SRS
- If a requirement is ambiguous, raise a clarification — do not guess

---

## 📄 Licence

Proprietary. © Pantropi Limited. Prepared for CPG Corporation Pte Ltd.
