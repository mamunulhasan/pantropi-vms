# Repository Structure

**Model:** single repository (monorepo), multi-module.
**Rationale:** the SRS scopes one application with one database. A monorepo keeps the requirements,
schema, backend and frontend versioned together, so a requirement change and its implementation land
in one atomic, traceable commit. TDD §4's "services" are **modules with enforced boundaries**, not
separately deployed processes — they can be split out later without redesign because the boundaries
are already enforced in CI.

> **Nothing below `apps/` or `libs/` exists yet.** This is the target structure that EPIC-01
> (F-01.2) creates. It is documented now so the backlog can reference concrete paths.

---

## Top level

```
pantropi-vms/
├── .github/                    # issue forms, PR template, labels, CODEOWNERS, workflows
├── docs/                       # source documents + project governance (see below)
├── scripts/                    # bootstrap and operational scripts
├── apps/                       # deployable applications
│   ├── vms-api/                # Java + Spring Boot backend  (TDD §3)
│   └── vms-web/                # Next.js frontend            (TDD §3)
├── libs/                       # shared, non-deployable libraries
├── db/                         # migrations + schema baseline
├── infra/                      # Docker, Kubernetes, environment configuration
├── tools/                      # ACS simulator, test fixtures, developer utilities
├── CLAUDE.md                   # working agreement
├── CONTRIBUTING.md
├── README.md
└── .gitignore
```

---

## `docs/` — documentation

```
docs/
├── VMS_Only_SRS_Project_Pinnacle.docx        # SOURCE OF TRUTH — requirements
├── VMS Technical Design Document.docx        # SOURCE OF TRUTH — design
├── vms_schema_postgresql.sql                 # SOURCE OF TRUTH — schema
├── project/
│   ├── 01-requirements-catalogue.md          # what may be built
│   ├── 02-traceability-matrix.md             # requirement → … → release
│   ├── 03-epics-and-backlog.md               # ⚠️ SUPERSEDED → backlog/00-epic-feature-index.md
│   ├── 04-milestones-and-release-plan.md     # ⚠️ SUPERSEDED → 12-release-plan.md
│   ├── 05-workflow-and-branching.md
│   ├── 06-governance-and-ceremonies.md
│   ├── 07-open-questions.md                  # the 19 TODOs
│   ├── 08-source-document-discrepancies.md   # D-01 … D-15
│   ├── 09-repository-structure.md            # this file
│   ├── 10-branch-strategy.md
│   ├── 11-labels-and-project-board.md
│   ├── 12-release-plan.md
│   └── backlog/
│       ├── 00-epic-feature-index.md          # 19 epics, 93 features
│       ├── phase-1-platform-foundation.md
│       ├── phase-2-visitor-registration-pass-generation.md
│       ├── phase-3-entry-verification-acs-integration.md
│       └── phase-4-reporting-notification.md
├── adr/                                      # architecture decision records
└── api/                                      # OpenAPI specs (generated + curated)
```

---

## `apps/vms-api/` — backend, Clean Architecture

Gradle/Maven multi-module. **Module dependencies point inward only**, enforced by architecture
fitness tests in CI (F-01.2), not by convention.

```
apps/vms-api/
├── vms-domain/                 # ── innermost. NO framework imports. ──
│   └── src/main/java/.../domain/
│       ├── masterdata/         # Building, Floor, Tenant, Reception, PassType…
│       ├── visitor/            # VisitorRequest (root), Visitor, Host
│       ├── credential/         # Credential (root), ValidityWindow, Restriction
│       ├── entry/              # Visit, CardIssuance, AccessEvent
│       ├── identity/           # User, Role, Permission
│       ├── notification/       # Notification
│       └── shared/             # value objects, domain events, base types
│
├── vms-application/            # ── use cases + PORTS ──
│   └── src/main/java/.../application/
│       ├── masterdata/
│       ├── visitor/
│       ├── credential/
│       ├── entry/
│       ├── notification/
│       ├── reporting/
│       ├── identity/
│       └── port/
│           ├── in/             # use-case interfaces (driving)
│           └── out/            # AcsPort, NotificationPort, repositories (driven)
│
├── vms-infrastructure/         # ── ADAPTERS ──
│   └── src/main/java/.../infrastructure/
│       ├── persistence/        # JPA entities, repository implementations
│       ├── acs/                # ⚠️ ACS ANTI-CORRUPTION LAYER — see below
│       ├── messaging/          # Kafka producers/consumers, outbox, dead-letter
│       ├── cache/              # Redis
│       ├── email/              # SMTP gateway adapter
│       ├── whatsapp/           # WhatsApp Business API adapter (flagged off, TODO-05)
│       ├── security/           # JWT, secrets manager
│       └── reporting/          # Excel/PDF generation
│
├── vms-interfaces/             # ── REST controllers, schedulers, event listeners ──
│   └── src/main/java/.../interfaces/
│       ├── rest/               # controllers, DTOs, exception handlers
│       ├── scheduler/          # expiry, reconciliation, sync jobs
│       └── event/              # inbound event listeners
│
├── vms-bootstrap/              # Spring Boot application entry point + wiring
└── vms-architecture-tests/     # ArchUnit fitness tests — the boundary enforcement
```

### The load-bearing rule

```
infrastructure/acs/  ←  the ONLY place an ACS type may exist
```

No ACS DTO, error code, enum, or vocabulary may appear in `vms-domain`, `vms-application`, or
`vms-interfaces`. This satisfies CON-01, CON-02 and NFR-MNT-01 (SRS B1), and it is the entire
mitigation for the unpublished ACS contract (TODO-02) — see
[ADR-0002](../adr/0002-acs-anti-corruption-layer.md).

**It is enforced by a failing build, not by a code review comment.**

### Dependency direction

```
interfaces ──┐
             ├──► application ──► domain
infrastructure ┘                    ▲
                                    │
        domain depends on NOTHING ──┘
```

---

## `apps/vms-web/` — frontend

```
apps/vms-web/
├── app/
│   ├── (tenant)/               # tenant portal — request submission
│   ├── (admin)/                # FM Admin — approval dashboard
│   ├── (reception)/            # floor + central reception workflows
│   ├── (display)/              # visitor-facing slave display (FR-VMS-15)
│   └── (auth)/
├── components/                 # shared component library (F-06.2)
├── lib/                        # API client, auth, hooks
├── styles/                     # design tokens, client branding (F-06.1)
└── tests/                      # component + E2E
```

Four role-driven route groups matching SRS §4.1's user interfaces, plus auth.

---

## `libs/`, `db/`, `infra/`, `tools/`

```
libs/
├── vms-contracts/              # shared API/event contract types
└── vms-testing/                # shared test fixtures and builders

db/
├── migrations/                 # versioned, forward-only (Flyway or Liquibase)
│   └── V001__baseline_schema.sql   # from docs/vms_schema_postgresql.sql
└── seed/                       # reference data per environment

infra/
├── docker/
│   └── docker-compose.yml      # PostgreSQL, Redis, Kafka (F-01.3)
├── k8s/                        # cloud deployment (TDD §9.1)
├── onprem/                     # single-host deployment (TDD §9.2)
└── config/                     # per-environment configuration (dev/staging/prod)

tools/
├── acs-simulator/              # ⭐ ACS simulator (F-11.2) — unblocks Phase 2 and 3
└── load-testing/               # NFR-SCL-01: 500 visitors/day, 120 concurrent users
```

The **ACS simulator** is first-class deliverable test infrastructure, not a throwaway stub. It is
what allows credential workflows to be built and tested while TODO-02 is unresolved.

---

## Conventions

| Concern | Convention |
|---|---|
| Java package root | `com.pantropi.vms` |
| Module naming | `vms-<layer>` |
| Test naming | `<Class>Test` (unit), `<Class>IT` (integration) |
| Migration naming | `V<nnn>__<snake_case_description>.sql`, forward-only |
| API base path | `/api/v1` |
| Schema | all tables in the `vms` PostgreSQL schema, per the baseline DDL |

## Deferred decisions

| Decision | Status | Resolves with |
|---|---|---|
| Split modules into separately deployed services | Deferred — boundaries enforced, so the split stays cheap | Scale evidence against NFR-SCL-01 |
| Kubernetes vs single-host as the primary target | Deferred | TODO-06 (deployment model + data residency) |
| Edge gateway component | **Not planned** | TODO-19 — TDD §9.1 currently contradicts SRS §1.2 (D-08) |
