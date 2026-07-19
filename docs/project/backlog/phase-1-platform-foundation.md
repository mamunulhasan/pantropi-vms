# Phase 1 — Platform Foundation

**Baseline:** B1 · **Milestone:** Phase 1 · **Contract:** [`00-epic-feature-index.md`](00-epic-feature-index.md)
**Sources:** SRS (authoritative for requirements) · TDD (design input) · `docs/vms_schema_postgresql.sql`

---

## Phase goal

Deliver a **deployable, secured, observable, empty application** — identity, authorization, master
data, audit and the portal shell — with no visitor workflow. At the end of Phase 1 a System
Administrator can log in, administer users and roles, maintain building/floor/tenant/reception
master data, and every state change is audited. Nothing about a visitor exists yet.

## TDD alignment

TDD §4.1 (Configuration & Master Data Service), §4.6 (cross-cutting services: identity, audit,
notification scaffolding), §3 (technology stack), §7 (JWT/RBAC), §9.3 (per-environment config),
§10 (observability). Technology per TDD §3: **Java 21 + Spring Boot** (domain/application/
infrastructure/interfaces per Clean Architecture), **PostgreSQL 14+**, **Redis** (session + master
data cache), **Kafka** (domain events), **Next.js** (portal), **Docker/Kubernetes**, **JWT/OAuth2**.

## Phase totals

| Metric | Value |
|---|---|
| Epics | 6 (EPIC-01 … EPIC-06) |
| Features | 33 |
| User stories | 42 |
| Development tasks | 127 |
| Total story points | 167 |
| Blocking TODOs carried | TODO-01, TODO-08, TODO-12, TODO-14, TODO-15 |

### Provenance mix

| Marker | Features | Note |
|---|---|---|
| `SRS` | 4 | FR-ADM-01, FR-ADM-02, FR-ADM-03, FR-API-03 (SRS B1) |
| `SRS-NFR` / `SRS-CON` | 5 | NFR-SEC-01, NFR-CMP-01, NFR-USA-01, CON-03 (SRS B1) |
| `ENABLER` | 8 | Justified against NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 (SRS B1) |
| `TDD-DERIVED` | 14 | **Not authorized for implementation** until TODO-01 is dispositioned |
| `BLOCKED` | 2 | F-03.4 (TODO-14), F-05.4 (TODO-12) |

> ⚠️ **EPIC-04 is entirely `TDD-DERIVED` and is backlog-only pending TODO-01.** It is estimated and
> sequenced so the plan has a realistic shape and cost. No EPIC-04 story may enter a sprint until
> TODO-01 is dispositioned. See the epic header for the full statement.

---

## Phase exit criteria

- [ ] A signed container image of the VMS backend and the Next.js portal deploys to the staging
      Kubernetes namespace from `develop` with no manual step.
- [ ] `main` and `develop` are protected; every required status check (build, unit, integration,
      coverage gate, SAST, dependency scan, commit lint, architecture fitness) is enforced.
- [ ] Architecture fitness tests fail the build on any inward-dependency violation and on any
      ACS-specific type appearing outside the ACS integration module (CON-01, CON-02 — SRS B1).
- [ ] The full `vms` schema baseline applies from an empty database through the migration tool, and
      re-applying is a no-op.
- [ ] A user can authenticate, receive a JWT, refresh it, and be logged out with the session
      revoked server-side.
- [ ] Every protected endpoint denies by default; an unauthenticated or under-privileged call
      returns 401/403 and writes an authorization-denial audit event.
- [ ] All six seeded roles exist with their permission grants; a System Administrator can
      administer users, roles and permission grants.
- [ ] Master data CRUD exists for buildings, floors, tenants, receptions, visitor types, pass types,
      holiday calendar and system settings — **behind a feature flag, disabled by default, pending
      TODO-01**.
- [ ] Every create/update/deactivate on an audited entity writes an append-only `vms.audit_logs` row
      with actor, before-state and after-state; the audit table rejects `UPDATE` and `DELETE`.
- [ ] No secret is present in source, container image layers, environment dumps, or logs; a
      secret-scanning check gates the pipeline.
- [ ] No PII (visitor or user name, email, phone, `id_document_ref`) appears in any application log
      at any level.
- [ ] TLS terminates on every ingress; database at-rest encryption is confirmed and documented.
- [ ] The portal shell renders branded, role-driven navigation and passes an automated WCAG 2.1 AA
      check with zero critical or serious violations.
- [ ] Observability: structured JSON logs with a correlation id propagated across HTTP and Kafka,
      RED metrics exported, and liveness/readiness probes green.
- [ ] The traceability matrix delivery log has a row for every story closed in this phase.

---


## EPIC-01 — Engineering Platform & Delivery Pipeline

| | |
|---|---|
| **Provenance** | `ENABLER` |
| **Justified by** | NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 (SRS B1); TDD §3, §9 |
| **Requirement IDs** | NFR-MNT-01 (SRS B1), NFR-SCL-01 (SRS B1), NFR-AVL-01 (SRS B1) |
| **Priority** | P0 |
| **Features** | 7 · **Stories** 9 · **Tasks** 27 · **Points** 38 |

**Goal.** Stand up the engineering substrate every later epic depends on: a governed repository whose
`git log` traces back to a requirement without opening GitHub, a Clean Architecture module skeleton
whose boundaries are enforced by automated fitness tests rather than convention, a reproducible local
stack (PostgreSQL, Redis, Kafka), a CI pipeline that gates on build, test, coverage, SAST and
dependency scanning, a versioned database migration path with the `vms` schema as its baseline,
container images with per-environment externalised configuration, and an observability foundation
that makes the running system legible. None of this is an SRS requirement in its own right — it is
the technical necessity that makes NFR-MNT-01's isolation claim and NFR-AVL-01's availability claim
verifiable instead of asserted. This epic is on the critical path for every other epic in the phase
and must land first.

> **TODO-06 / TODO-19 note.** The deployment target (hybrid cloud-and-edge vs fully on-premise) and
> gate decision authority are unresolved. F-01.6 therefore targets a **portable** container plus
> Kubernetes manifest set with no cloud-provider-specific service dependency. No edge-gateway
> component is built in this phase.

---

### F-01.1 — Repository, branching & commit governance

Establish the git-flow repository, branch protection, and the commit/PR conventions that carry the
requirement to story to commit traceability chain.

`ENABLER` · justified by NFR-MNT-01 (SRS B1) · P0 · 5 pts · 2 stories · 5 tasks

#### US-01.1.1 — Governed repository and branch protection

**As a** System Administrator **I want** the VMS repository configured with git-flow branches and
enforced protection rules **so that** no unreviewed or untraceable change can reach `develop` or
`main`.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `ENABLER` — justified by NFR-MNT-01 (SRS B1); workflow doc §2 |
| **Dependencies** | — |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a fresh clone, **When** I inspect the repository, **Then** `main` and `develop`
  exist, `develop` is the default branch, and the git-flow branch prefixes (`feature/`, `bugfix/`,
  `release/`, `hotfix/`, `spike/`) are documented in `CONTRIBUTING.md`.
- **AC-2 — Given** branch protection is configured, **When** a pull request targets `develop` or
  `main`, **Then** merge is blocked until at least one CODEOWNER approval, all required status
  checks pass, the branch is up to date, and all conversations are resolved.
- **AC-3 — Given** a CODEOWNERS file, **When** a PR touches an `area/acs-integration` or `security`
  path, **Then** two approving reviews are required rather than one.
- **AC-4 (negative) — Given** I am a repository administrator, **When** I attempt to push directly
  to `develop` or `main`, **Then** the push is rejected because protection rules include
  administrators.
- **AC-5 (negative) — Given** a PR whose approving review is stale after a new commit, **When** the
  author pushes, **Then** the approval is dismissed and re-review is required.

**Development Tasks**

**T-01.1.1.1 — Create repository structure and git-flow branches** · `P0` · `1 pts` · deps: `—`
- **Description:** Initialise the repository with `main` and `develop`, set `develop` as default, and
  add `.gitignore`, `.gitattributes` (LF normalisation) and a `CONTRIBUTING.md` documenting the
  branch model and naming convention from workflow doc §2.
- **Acceptance Criteria:** `main` and `develop` exist; `develop` is default; `CONTRIBUTING.md`
  documents all five branch prefixes; a worked example carries a user story id.
- **Dependencies:** —

**T-01.1.1.2 — Author CODEOWNERS with real team handles** · `P0` · `1 pts` · deps: `T-01.1.1.1`
- **Description:** Create `.github/CODEOWNERS` mapping each Clean Architecture module and the ACS
  integration module to an owning team. Replace the placeholder `@pantropi/vms-*` handles flagged in
  workflow doc §2 with real GitHub teams before protection is enabled.
- **Acceptance Criteria:** Every top-level source path has an owner; no placeholder handle remains;
  a test PR successfully requests review from the mapped team.
- **Dependencies:** T-01.1.1.1

**T-01.1.1.3 — Apply branch protection rules as code** · `P0` · `1 pts` · deps: `T-01.1.1.2`
- **Description:** Configure protection on `main` and `develop` — required PR, at least one approval
  (two for ACS/security paths), CODEOWNER review, required status checks, up-to-date branch,
  conversation resolution, stale-approval dismissal, include administrators. Record the configuration
  in a version-controlled settings file so it is reviewable and restorable.
- **Acceptance Criteria:** Direct push to `develop` by an admin is rejected; a PR without CODEOWNER
  approval cannot merge; protection configuration is committed to the repository.
- **Dependencies:** T-01.1.1.2

#### US-01.1.2 — Commit and pull-request traceability gates

**As a** System Administrator **I want** commit messages and pull requests mechanically checked for
their user story and requirement references **so that** the traceability chain from requirement to
merge cannot silently break.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 2 |
| **Provenance** | `ENABLER` — justified by NFR-MNT-01 (SRS B1); workflow doc §1, §3 |
| **Dependencies** | US-01.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a commit message of the form `feat(identity): issue jwt on login (US-02.1.1)`,
  **When** the commit-lint check runs, **Then** it passes: the type is in the allowed set, the scope
  is in the TDD §4 scope list, and the subject is imperative lowercase, 72 characters or fewer, with
  no trailing period.
- **AC-2 — Given** a `feat` or `fix` commit, **When** the message contains no user story id of the
  form `US-NN.N.N`, **Then** the check fails with a message naming the rule.
- **AC-3 — Given** a pull request, **When** it is opened, **Then** the PR template requires the user
  story id, the qualified requirement reference (for example `FR-ADM-02 (SRS B1)`), a test evidence
  section, and a security checklist.
- **AC-4 (negative) — Given** a commit body citing a bare `FR-ADM-02` without the `(SRS B1)`
  qualifier, **When** the check runs, **Then** it fails, citing discrepancy D-02.

**Development Tasks**

**T-01.1.2.1 — Conventional Commits lint in CI and as a local hook** · `P0` · `1 pts` · deps: `T-01.1.1.3`
- **Description:** Add a commit-message linter configured with the type list, the TDD §4 scope list
  (`master-data`, `visitor`, `credential`, `entry`, `acs`, `notification`, `reporting`, `identity`,
  `audit`, `frontend`, `platform`, `db`), the story-id rule for `feat`/`fix`, and the qualified
  requirement-reference rule. Wire it as a CI status check and an optional local pre-commit hook.
- **Acceptance Criteria:** A conforming message passes; a missing story id on a `feat` fails; a bare
  requirement reference fails; the check is registered as required on `develop`.
- **Dependencies:** T-01.1.1.3

**T-01.1.2.2 — Issue and pull-request templates carrying the traceability fields** · `P0` · `1 pts` · deps: `T-01.1.1.1`
- **Description:** Add `.github/ISSUE_TEMPLATE` entries for epic, feature, user-story and task types
  with a mandatory parent-id field, and a `PULL_REQUEST_TEMPLATE.md` with the story id, qualified
  requirement, Definition of Done checklist (workflow doc §6), security checklist and
  traceability-matrix update confirmation.
- **Acceptance Criteria:** Each template renders with its required fields; the PR template includes
  every DoD line item; a PR with unchecked DoD boxes is visibly incomplete to the reviewer.
- **Dependencies:** T-01.1.1.1

---

### F-01.2 — Clean Architecture module skeleton & boundary enforcement

Create the four-layer module structure and the automated fitness tests that make the boundaries real.

`ENABLER` / NFR-MNT-01 (SRS B1) · P0 · 8 pts · 2 stories · 6 tasks

#### US-01.2.1 — Clean Architecture module skeleton

**As a** System Administrator **I want** the backend organised into `domain`, `application`,
`infrastructure` and `interfaces` modules with dependencies pointing inward only **so that** a
change to the ACS API contract is isolated to the integration layer without touching workflow logic.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` — NFR-MNT-01 (SRS B1), CON-01 (SRS B1), CON-02 (SRS B1); TDD §3 |
| **Dependencies** | US-01.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the multi-module build, **When** I inspect module dependencies, **Then** `domain`
  declares no dependency on `application`, `infrastructure` or `interfaces`, and no Spring or JPA
  dependency at all.
- **AC-2 — Given** the bounded contexts of workflow doc §8, **When** I inspect the package layout,
  **Then** each context (`masterdata`, `visitor`, `credential`, `entry`, `acs`, `notification`,
  `reporting`, `identity`) has its own package tree under each layer, with Phase 2 to 4 contexts
  present but empty.
- **AC-3 — Given** the `application` layer, **When** an outbound dependency is needed, **Then** it is
  expressed as a port interface in `application` and implemented as an adapter in `infrastructure`.
- **AC-4 — Given** a Spring Boot application module, **When** it starts against an empty database,
  **Then** the context loads and `/actuator/health` returns UP with no business endpoint registered.
- **AC-5 (negative) — Given** a developer adds a JPA entity annotation to a `domain` class, **When**
  the build runs, **Then** compilation or the fitness test fails because `domain` has no framework
  dependency on its compile classpath.

**Development Tasks**

**T-01.2.1.1 — Multi-module build with inward-only dependency declarations** · `P0` · `2 pts` · deps: `T-01.1.1.1`
- **Description:** Create the Gradle multi-module build with `vms-domain`, `vms-application`,
  `vms-infrastructure`, `vms-interfaces` and a thin `vms-bootstrap` application module. Declare
  dependencies inward only. Keep the Spring Boot BOM and JPA off the `vms-domain` classpath entirely.
- **Acceptance Criteria:** A full build succeeds; the `vms-domain` compile classpath contains no
  Spring or Jakarta Persistence artifact; `vms-bootstrap` is the only module producing a runnable jar.
- **Dependencies:** T-01.1.1.1

**T-01.2.1.2 — Bounded-context package skeleton** · `P0` · `1 pts` · deps: `T-01.2.1.1`
- **Description:** Lay out the per-context package trees under each layer per workflow doc §8, with a
  `package-info` in each recording the context's aggregate roots and what it owns. Include the `acs`
  context so the F-11.1 port has a home when it is pulled forward.
- **Acceptance Criteria:** Eight context packages exist under each of the four layers; each carries a
  `package-info` naming its aggregate roots; the `acs` context contains only the port package.
- **Dependencies:** T-01.2.1.1

**T-01.2.1.3 — Spring Boot bootstrap, profiles and port/adapter wiring convention** · `P0` · `2 pts` · deps: `T-01.2.1.2`
- **Description:** Add the Spring Boot bootstrap module with `local`, `test`, `staging` and `prod`
  profiles, component scanning restricted to `infrastructure` and `interfaces`, and a documented
  convention for declaring a port in `application` and registering its adapter bean in
  `infrastructure`. Add one reference port/adapter pair (a clock provider) to prove the pattern.
- **Acceptance Criteria:** The application starts under the `local` profile against an empty
  database; `/actuator/health` returns UP; the reference port resolves to its adapter by injection;
  no `application` class is annotated as a Spring component.
- **Dependencies:** T-01.2.1.2

#### US-01.2.2 — Architecture fitness tests

**As a** System Administrator **I want** the layer and ACS-boundary rules enforced by tests that run
on every commit **so that** a boundary violation fails the build instead of surviving code review.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `ENABLER` — NFR-MNT-01 (SRS B1), CON-01 (SRS B1), CON-02 (SRS B1); workflow doc §7 |
| **Dependencies** | US-01.2.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the fitness test suite, **When** it runs, **Then** it asserts that `domain`
  depends on nothing outward, `domain` imports no framework package, outbound concerns in
  `application` are expressed as ports, and no context reads another context's persistence types.
- **AC-2 — Given** the ACS boundary rule, **When** the suite runs, **Then** it asserts no type whose
  name or package identifies the ACS vendor exists outside the `acs` integration module.
- **AC-3 — Given** the suite, **When** it is registered in CI, **Then** it is a required status check
  on `develop` and `main`.
- **AC-4 (negative) — Given** a deliberately introduced violation (an `interfaces` type imported from
  `domain`), **When** the suite runs, **Then** it fails and the failure message names the offending
  class and the rule it broke.

**Development Tasks**

**T-01.2.2.1 — Layer dependency and framework-freedom rules** · `P0` · `1 pts` · deps: `T-01.2.1.3`
- **Description:** Implement ArchUnit rules for inward-only layer dependencies and for `domain`
  importing no Spring, Jakarta Persistence or other framework package.
- **Acceptance Criteria:** Rules pass on the clean skeleton; each rule fails with a named class when
  a violation is injected; rules live in a dedicated `architecture` test source set.
- **Dependencies:** T-01.2.1.3

**T-01.2.2.2 — ACS-boundary and cross-context isolation rules** · `P0` · `1 pts` · deps: `T-01.2.2.1`
- **Description:** Implement the rule that no ACS-specific type exists outside the `acs` module, and
  the rule that a context's repository and entity types are referenced only from within that context
  — the mechanical expression of CON-01 (SRS B1) and CON-02 (SRS B1), and the standing mitigation for
  TODO-02.
- **Acceptance Criteria:** Both rules pass on the skeleton; an ACS DTO placed in the `visitor`
  context fails the build; a `masterdata` entity referenced from `identity` fails the build.
- **Dependencies:** T-01.2.2.1

**T-01.2.2.3 — Register fitness tests as a required CI check** · `P0` · `1 pts` · deps: `T-01.2.2.2`
- **Description:** Wire the `architecture` test source set into the CI workflow as its own job and
  register it as a required status check on the protected branches.
- **Acceptance Criteria:** The job runs on every PR and appears in the required-checks list; a PR
  with a boundary violation cannot merge.
- **Dependencies:** T-01.2.2.2, T-01.1.1.3

---

### F-01.3 — Local development environment (PostgreSQL, Redis, Kafka)

A one-command, reproducible local stack and the integration-test harness that runs against it.

`ENABLER` · justified by NFR-MNT-01 (SRS B1) · P0 · 5 pts · 1 story · 4 tasks

#### US-01.3.1 — Reproducible local stack and integration test harness

**As a** System Administrator **I want** PostgreSQL, Redis and Kafka to start locally with one
command and integration tests to run against real instances **so that** every developer and CI agent
tests against the same infrastructure the system actually uses.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` — NFR-MNT-01 (SRS B1); TDD §3, §9.3; workflow doc §9 |
| **Dependencies** | US-01.2.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a clean workstation with Docker, **When** I run the documented compose command,
  **Then** PostgreSQL 14+ with the `pgcrypto` and `citext` extensions, Redis and a single-broker
  Kafka start and report healthy within the documented startup budget.
- **AC-2 — Given** the stack is running, **When** I start the application under the `local` profile,
  **Then** it connects to all three, applies migrations, and reports UP.
- **AC-3 — Given** the integration test source set, **When** it runs, **Then** it provisions
  PostgreSQL via Testcontainers, applies the migration baseline, and tears down cleanly, with no
  dependency on the developer's running compose stack.
- **AC-4 — Given** the local stack, **When** I inspect its configuration, **Then** every credential
  is a documented non-production default supplied by environment variable, and none is a value also
  used in staging or production.
- **AC-5 (negative) — Given** Kafka is stopped, **When** the application starts, **Then** it starts
  and reports readiness as DOWN with a clear cause rather than crash-looping.

**Development Tasks**

**T-01.3.1.1 — Docker Compose stack for PostgreSQL, Redis and Kafka** · `P0` · `2 pts` · deps: `T-01.2.1.3`
- **Description:** Author `docker-compose.yml` pinning image versions for PostgreSQL 14+, Redis and
  Kafka, with health checks, named volumes, an init script enabling `pgcrypto` and `citext` and
  creating the `vms` schema owner role, and non-production credentials injected from a committed
  `.env.example` (never a real `.env`).
- **Acceptance Criteria:** One command brings all three to healthy; the `vms` schema owner exists;
  extensions are present; `.env` is git-ignored and `.env.example` contains no real secret.
- **Dependencies:** T-01.2.1.3

**T-01.3.1.2 — Local profile configuration and connectivity smoke check** · `P0` · `1 pts` · deps: `T-01.3.1.1`
- **Description:** Configure the `local` Spring profile with datasource, Redis and Kafka connection
  properties sourced from environment variables, and add a startup smoke check that verifies all
  three connections and logs the outcome without logging any credential.
- **Acceptance Criteria:** The application connects to all three under `local`; the smoke check
  output names each dependency and its status; no connection string with a password appears in logs.
- **Dependencies:** T-01.3.1.1

**T-01.3.1.3 — Testcontainers integration test harness** · `P0` · `1 pts` · deps: `T-01.3.1.2`
- **Description:** Add an `integrationTest` source set with a reusable Testcontainers base class that
  starts PostgreSQL, applies the migration baseline, and exposes a per-test transactional rollback or
  truncation strategy. Add Redis and Kafka containers behind opt-in annotations so tests that do not
  need them stay fast.
- **Acceptance Criteria:** A sample integration test passes on a machine with no compose stack
  running; containers are reused within a run and torn down after; the suite is wired into the build.
- **Dependencies:** T-01.3.1.2

**T-01.3.1.4 — Degraded-dependency startup behaviour** · `P1` · `1 pts` · deps: `T-01.3.1.2`
- **Description:** Configure the application so a missing Kafka or Redis at startup produces a
  readiness-DOWN state with a named cause rather than a fatal context failure, supporting the
  NFR-AVL-01 (SRS B1) claim that VMS availability is independent of downstream availability.
- **Acceptance Criteria:** With Kafka stopped the application starts and readiness reports DOWN
  naming Kafka; when Kafka returns, readiness recovers without a restart.
- **Dependencies:** T-01.3.1.2

---

### F-01.4 — CI pipeline — build, test, coverage, SAST

The pipeline that turns the Definition of Done into enforced status checks.

`ENABLER` · justified by NFR-MNT-01 (SRS B1) · P0 · 5 pts · 1 story · 3 tasks

#### US-01.4.1 — Continuous integration pipeline with quality and security gates

**As a** System Administrator **I want** every pull request to run build, unit tests, integration
tests, a coverage gate, SAST and a dependency scan **so that** no change merges without mechanical
evidence that it meets the Definition of Done.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` — NFR-MNT-01 (SRS B1); workflow doc §2, §6, §9 |
| **Dependencies** | US-01.2.2, US-01.3.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a pull request to `develop`, **When** CI runs, **Then** jobs execute for compile,
  unit tests, integration tests (Testcontainers), architecture fitness tests, coverage, SAST,
  dependency vulnerability scan and secret scanning, and each reports an independent status.
- **AC-2 — Given** the coverage gate, **When** a PR reduces line or branch coverage below the agreed
  baseline, **Then** the gate fails and names the regressing modules.
- **AC-3 — Given** the dependency scan, **When** a dependency with a known critical or high
  vulnerability is introduced, **Then** the build fails with the advisory identifier.
- **AC-4 (negative) — Given** a PR that adds an API key literal to a properties file, **When** the
  secret-scanning job runs, **Then** the build fails and the finding is reported without echoing the
  secret value into the build log.
- **AC-5 (negative) — Given** a flaky or failing integration test, **When** CI runs, **Then** the job
  fails; there is no retry-until-green behaviour configured.

**Development Tasks**

**T-01.4.1.1 — Core build, unit and integration test workflow** · `P0` · `2 pts` · deps: `T-01.3.1.3`
- **Description:** Author the CI workflow with a dependency-cached build job, a unit test job with no
  Spring context or I/O, and an integration test job running the Testcontainers suite, publishing
  JUnit results and failing fast on compile errors.
- **Acceptance Criteria:** All three jobs run on every PR and on pushes to `develop`; test results
  are visible in the PR; total pipeline duration is recorded as a baseline for later tuning.
- **Dependencies:** T-01.3.1.3

**T-01.4.1.2 — Coverage measurement and non-regression gate** · `P0` · `1 pts` · deps: `T-01.4.1.1`
- **Description:** Add JaCoCo aggregation across modules, publish the report as a build artefact, and
  enforce a per-module minimum plus a non-regression rule against the `develop` baseline, with
  generated and configuration classes excluded from the denominator.
- **Acceptance Criteria:** Coverage report is produced and downloadable; a deliberate coverage drop
  fails the gate; exclusions are declared in a reviewed configuration file, not inline suppressions.
- **Dependencies:** T-01.4.1.1

**T-01.4.1.3 — SAST, dependency scanning and secret scanning jobs** · `P0` · `2 pts` · deps: `T-01.4.1.1`
- **Description:** Add static application security testing over Java and TypeScript sources, a
  software composition analysis job failing on critical and high advisories, and a repository secret
  scanner covering history. Configure findings to surface as annotations with no secret values echoed
  into logs.
- **Acceptance Criteria:** All three jobs run on every PR and are registered as required checks; a
  seeded vulnerable dependency fails the build; a seeded credential literal fails the build; no
  finding output contains the raw secret.
- **Dependencies:** T-01.4.1.1

---

### F-01.5 — Database migration framework & schema baseline

Versioned, forward-only migrations with `docs/vms_schema_postgresql.sql` as the baseline.

`ENABLER` · justified by NFR-MNT-01 (SRS B1) · P0 · 5 pts · 1 story · 3 tasks

#### US-01.5.1 — Versioned migrations and the `vms` schema baseline

**As a** System Administrator **I want** the database schema applied and evolved through versioned
migrations **so that** every environment converges on an identical, auditable schema state.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` — NFR-MNT-01 (SRS B1); `docs/vms_schema_postgresql.sql`; TDD §3 |
| **Dependencies** | US-01.3.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** an empty PostgreSQL 14+ database, **When** migrations run, **Then** the `vms`
  schema is created with every table, enum type, index, trigger and comment from
  `docs/vms_schema_postgresql.sql`, and the migration history table records the baseline version.
- **AC-2 — Given** a database already at the baseline, **When** migrations run again, **Then** the
  run is a no-op and the application starts normally.
- **AC-3 — Given** the seed data in schema §14, **When** the baseline applies, **Then** the five
  roles, eleven permissions, four visitor types, three pass types and four system settings exist
  exactly once, and re-running does not duplicate them.
- **AC-4 — Given** migrations run at application startup, **When** a migration fails, **Then** the
  application refuses to start and the failure names the migration version and the SQL error.
- **AC-5 (negative) — Given** a developer edits an already-applied migration file, **When**
  migrations run against a database that has it, **Then** the checksum validation fails and the run
  aborts without partially applying anything.

**Development Tasks**

**T-01.5.1.1 — Migration tool integration and execution policy** · `P0` · `2 pts` · deps: `T-01.3.1.2`
- **Description:** Integrate Flyway into the bootstrap module and the build, configured for the `vms`
  schema, forward-only migrations, checksum validation on, clean disabled in every profile, and a
  documented naming convention (`V<version>__<snake_case_description>.sql`). Add a Gradle task to run
  migrations independently of application startup for pipeline use.
- **Acceptance Criteria:** Migrations run at startup and via the standalone task; `clean` is disabled
  in all profiles including `local`; checksum validation aborts on a modified applied migration.
- **Dependencies:** T-01.3.1.2

**T-01.5.1.2 — Baseline migration from the published schema** · `P0` · `2 pts` · deps: `T-01.5.1.1`
- **Description:** Translate `docs/vms_schema_postgresql.sql` into the `V1__baseline.sql` migration —
  extensions, `vms` schema, all fourteen enum types, the `set_updated_at` trigger function, all
  master data, identity, visitor, credential, card, access event, ACS integration, notification,
  settings and audit tables, indexes, and the `updated_at` trigger loop. Preserve table comments,
  which carry the requirement references.
- **Acceptance Criteria:** Applying to an empty database produces a schema structurally identical to
  the published DDL, verified by an automated schema-diff test; all table comments are preserved.
- **Dependencies:** T-01.5.1.1

**T-01.5.1.3 — Idempotent reference seed migration** · `P0` · `1 pts` · deps: `T-01.5.1.2`
- **Description:** Author `V2__reference_seed.sql` carrying schema §14 seed data (roles, permissions,
  visitor types, pass types, system settings) using conflict-tolerant inserts keyed on the natural
  unique columns so re-running is safe. Add the `SYSTEM_ADMIN` bootstrap consideration as a comment
  only — the actual bootstrap account is created in US-02.4.1, never seeded with a fixed password.
- **Acceptance Criteria:** Seed rows exist exactly once after one or many runs; no user row and no
  password hash is present in any migration file.
- **Dependencies:** T-01.5.1.2

---

### F-01.6 — Containerisation & per-environment configuration

Portable container images and externalised configuration for local, staging and production.

`ENABLER` / TDD §9.3 · P0 · 5 pts · 1 story · 3 tasks

#### US-01.6.1 — Container images and externalised per-environment configuration

**As a** System Administrator **I want** the backend and portal packaged as portable container images
with all environment-specific values externalised **so that** the same artefact can be promoted from
staging to production without a rebuild.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` — NFR-AVL-01 (SRS B1), NFR-SCL-01 (SRS B1); TDD §9.3 |
| **Dependencies** | US-01.4.1, US-01.5.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a merge to `develop`, **When** the pipeline runs, **Then** it builds a backend
  image and a Next.js portal image, tags them with the commit sha and a semantic version, and pushes
  them to the registry.
- **AC-2 — Given** an image, **When** it runs, **Then** the process runs as a non-root user with a
  read-only root filesystem and no shell-based entrypoint indirection.
- **AC-3 — Given** the Kubernetes manifests, **When** they are applied to the staging namespace,
  **Then** the deployment becomes ready, liveness and readiness probes pass, and resource requests
  and limits are set on every container.
- **AC-4 — Given** environment differences, **When** I diff the staging and production manifests,
  **Then** they differ only in configuration inputs (ConfigMap and Secret references, replica count,
  resource sizing) and never in the image digest.
- **AC-5 (negative) — Given** an image, **When** I inspect its layers and its default environment,
  **Then** no database password, JWT signing key, API key or other secret is present in any layer or
  baked environment variable.

**Development Tasks**

**T-01.6.1.1 — Hardened container images for backend and portal** · `P0` · `2 pts` · deps: `T-01.4.1.1`
- **Description:** Author multi-stage Dockerfiles producing a minimal-base JRE image for the Spring
  Boot backend and a standalone-output image for the Next.js portal. Run as a non-root UID, set a
  read-only root filesystem with explicit writable mounts, drop all Linux capabilities, and generate
  an SBOM per image.
- **Acceptance Criteria:** Both images build reproducibly in CI; both run as non-root; an image scan
  reports no critical base-image vulnerability; an SBOM artefact is published per image.
- **Dependencies:** T-01.4.1.1

**T-01.6.1.2 — Externalised configuration model across profiles** · `P0` · `2 pts` · deps: `T-01.6.1.1`
- **Description:** Define the configuration contract per TDD §9.3 — every environment-specific value
  (datasource URL and credentials, Redis, Kafka bootstrap servers, JWT issuer and key reference,
  branding, feature flags) supplied by environment variable or mounted file, with typed configuration
  properties classes and fail-fast validation at startup. Provide a documented required-variable
  inventory. No cloud-provider-specific service is a hard dependency, pending TODO-06.
- **Acceptance Criteria:** Starting with a required variable missing fails at startup naming the
  variable; no environment-specific literal remains in packaged configuration; the inventory
  documents every variable, its purpose and whether it is a secret.
- **Dependencies:** T-01.6.1.1

**T-01.6.1.3 — Kubernetes manifests and staging deployment** · `P0` · `1 pts` · deps: `T-01.6.1.2`
- **Description:** Author portable Kubernetes manifests (Deployment, Service, Ingress with TLS,
  ConfigMap, Secret references, HorizontalPodAutoscaler sized for NFR-SCL-01's 120 concurrent
  reception users) with an overlay per environment, and wire an automated staging deploy from
  `develop`.
- **Acceptance Criteria:** Staging deploy from `develop` succeeds unattended; probes pass; overlays
  differ only in configuration and sizing; the same image digest is used across environments.
- **Dependencies:** T-01.6.1.2

---

### F-01.7 — Observability foundation — logging, metrics, health, correlation ids

Structured logs, RED metrics, health probes, and a correlation id that survives HTTP and Kafka hops.

`ENABLER` / TDD §10 · P0 · 5 pts · 1 story · 3 tasks

#### US-01.7.1 — Structured logging, correlation ids, metrics and health probes

**As a** System Administrator **I want** structured logs carrying a correlation id, RED metrics, and
health probes **so that** I can diagnose a production incident without adding instrumentation after
the fact — and without any personal data being written to a log.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` — NFR-AVL-01 (SRS B1), NFR-CMP-01 (SRS B1); TDD §10 |
| **Dependencies** | US-01.2.1, US-01.6.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** any inbound HTTP request, **When** it is handled, **Then** every log line emitted
  during it is JSON and carries the same correlation id, taken from the inbound header if present or
  generated if absent, and the id is returned on the response.
- **AC-2 — Given** a request that publishes a domain event to Kafka, **When** a consumer handles that
  event, **Then** the consumer's log lines carry the originating correlation id propagated through
  the message headers.
- **AC-3 — Given** the metrics endpoint, **When** it is scraped, **Then** request rate, error rate
  and latency distribution are exposed per endpoint, along with JVM, datasource pool, Redis and Kafka
  consumer-lag metrics.
- **AC-4 — Given** liveness and readiness probes, **When** the database is unreachable, **Then**
  readiness reports DOWN naming the datasource while liveness stays UP, so the pod is removed from
  service without being killed.
- **AC-5 (negative) — Given** a request carrying a user email, a visitor name or a password field,
  **When** any log line is emitted at any level including DEBUG, **Then** the value is absent or
  redacted, and an automated test asserts the redaction for a defined list of sensitive field names.

**Development Tasks**

**T-01.7.1.1 — Structured JSON logging with PII redaction** · `P0` · `2 pts` · deps: `T-01.2.1.3`
- **Description:** Configure JSON-encoded logging with a consistent field set (timestamp, level,
  logger, correlation id, user id, message), a redaction layer that masks a declared list of
  sensitive keys (`password`, `password_hash`, `token`, `authorization`, `email`, `phone`,
  `full_name`, `id_document_ref`, `qr_payload`), and log levels per profile with DEBUG unavailable in
  production configuration.
- **Acceptance Criteria:** Log output parses as JSON; a test logging a map containing every declared
  sensitive key produces no plaintext value; production profile cannot emit DEBUG.
- **Dependencies:** T-01.2.1.3

**T-01.7.1.2 — Correlation id propagation across HTTP and Kafka** · `P0` · `2 pts` · deps: `T-01.7.1.1`
- **Description:** Add a servlet filter that accepts or generates a correlation id into the logging
  context and echoes it on the response, a Kafka producer interceptor that writes it into message
  headers, and a consumer interceptor that restores it — with the context cleared on every thread
  boundary to prevent leakage between requests.
- **Acceptance Criteria:** An end-to-end test asserts the same id appears in producer and consumer
  logs; the response header carries the id; a thread returned to the pool carries no residual id.
- **Dependencies:** T-01.7.1.1

**T-01.7.1.3 — Metrics export and health probe endpoints** · `P0` · `1 pts` · deps: `T-01.7.1.1`
- **Description:** Expose Micrometer metrics in Prometheus format on a management port separate from
  the application port, with RED metrics per endpoint plus JVM, connection pool, Redis and Kafka
  gauges, and split liveness and readiness health groups with datasource, Redis and Kafka indicators
  contributing to readiness only.
- **Acceptance Criteria:** The metrics endpoint is not exposed on the public ingress; readiness
  reports DOWN with a named cause when the datasource is stopped while liveness stays UP; a scrape
  configuration is committed with the manifests.
- **Dependencies:** T-01.7.1.1

---

## EPIC-02 — Identity, Authentication & Session Management

| | |
|---|---|
| **Provenance** | `SRS` / `SRS-NFR` |
| **Requirement IDs** | FR-ADM-01 (SRS B1), FR-ADM-02 (SRS B1), NFR-SEC-01 (SRS B1), NFR-SCL-01 (SRS B1) |
| **Priority** | P0 |
| **Features** | 4 · **Stories** 6 · **Tasks** 19 · **Points** 26 |
| **Open questions** | TODO-08 (meaning of "120 logins"), TODO-15 (authentication mechanism) |

**Goal.** Give VMS a real identity: users authenticate against `vms.users`, receive a JWT, hold a
server-revocable session, and are subject to an account security policy. FR-ADM-02 (SRS B1) requires
120 floor-reception logins plus one central admin login, each authenticating from their floor's
reception PC — but TODO-08 records three incompatible readings of that sentence (120 named accounts,
120 shared per-floor accounts, or 120 concurrent sessions as a licence ceiling) with materially
different audit consequences. This epic therefore builds the **named-account** model, which is the
only reading that lets an action be attributed to a person, while keeping the provisioning mechanism
and any concurrency ceiling behind configuration so a different disposition does not require a
schema change. TODO-15 similarly leaves open whether client SSO or Active Directory federation is
required; the local-account path implied by `vms.users.password_hash` is built behind an
authentication-provider port so a federated adapter can be added without touching the login use case.

> **Security posture for this epic.** No credential is ever logged, echoed in an error, or returned
> in an API response. Authentication failures are generic and constant-time in their messaging.
> Every authentication and account state change writes an audit event (EPIC-05).

---

### F-02.1 — User authentication & JWT session lifecycle

Login, token issuance, refresh, logout, and server-side session revocation.

`SRS` FR-ADM-02 (SRS B1) · TDD §7 · P0 · 10 pts · 2 stories · 7 tasks

#### US-02.1.1 — Authenticate and receive a session token

**As a** Floor Receptionist **I want** to log in with my username and password and receive a session
token **so that** I can use the VMS portal from my floor's reception PC.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-ADM-02 (SRS B1); NFR-SEC-01 (SRS B1); TDD §7; schema `vms.users` |
| **Dependencies** | US-01.5.1, US-01.7.1 |
| **Blocked by** | TODO-15 — authentication mechanism (local accounts vs client SSO/AD federation) unconfirmed. Built behind an authentication-provider port; the local-account adapter is the only implementation in this phase. |

**Acceptance Criteria**
- **AC-1 — Given** an active user in `vms.users` with a valid password, **When** they post correct
  credentials to the login endpoint, **Then** a signed JWT access token and an opaque refresh token
  are returned, `last_login_at` is updated, and an authentication-success audit event is written.
- **AC-2 — Given** a successful login, **When** I inspect the access token claims, **Then** they
  carry the user id, username, role code and the token's issuer, audience, issued-at and expiry, and
  they carry no password hash, no email and no other personal data beyond the display name.
- **AC-3 — Given** a valid access token, **When** it is presented to a protected endpoint, **Then**
  the signature, issuer, audience and expiry are all validated before the request is handled.
- **AC-4 (negative) — Given** a wrong password, a non-existent username, or a user whose `is_active`
  is false, **When** login is attempted, **Then** the response is an identical generic failure in all
  three cases, no detail distinguishes them, and an authentication-failure audit event records the
  attempted username and source IP.
- **AC-5 (negative) — Given** a token with a tampered payload or signed by an unknown key, **When**
  it is presented, **Then** the request is rejected with 401 and the token contents are not logged.

**Development Tasks**

**T-02.1.1.1 — Identity domain model and user repository** · `P0` · `1 pts` · deps: `T-01.5.1.3`
- **Description:** Model the `User` aggregate and `Role` value object in the `identity` domain
  context with no framework annotations, and implement the persistence adapter over `vms.users` and
  `vms.roles` in `infrastructure`, honouring the `citext` case-insensitive semantics of `username`
  and `email`.
- **Acceptance Criteria:** Domain types compile with no framework import; lookup by username is
  case-insensitive; `password_hash` is never exposed on a domain accessor returning it to
  `interfaces`; integration tests cover found, not-found and inactive cases.
- **Dependencies:** T-01.5.1.3

**T-02.1.1.2 — Authentication provider port and local-account adapter** · `P0` · `2 pts` · deps: `T-02.1.1.1`
- **Description:** Define an authentication-provider port in `application` taking a credential and
  returning an authenticated principal or a generic failure, and implement the local-account adapter
  performing an Argon2id (or bcrypt with agreed cost) verification against `password_hash`, with a
  constant-time comparison and a dummy verification on unknown users to equalise response timing.
  The port exists specifically so a TODO-15 federation decision is an adapter change.
- **Acceptance Criteria:** Correct credentials authenticate; wrong password, unknown user and
  inactive user all return the same generic failure; timing variance between unknown-user and
  wrong-password paths is within the agreed tolerance; no adapter log line contains a credential.
- **Dependencies:** T-02.1.1.1

**T-02.1.1.3 — JWT issuance and validation** · `P0` · `2 pts` · deps: `T-02.1.1.2`
- **Description:** Implement asymmetrically signed JWT issuance with a short access-token lifetime,
  configured issuer and audience, a key id header for rotation, and a resource-server validation
  filter checking signature, issuer, audience, expiry and not-before. The signing key is resolved
  through the secrets mechanism of F-05.2, never from a properties file.
- **Acceptance Criteria:** A valid token authenticates; expired, wrong-audience, wrong-issuer and
  tampered tokens are each rejected with 401; claims contain no personal data beyond display name;
  the signing key is absent from configuration files and from logs.
- **Dependencies:** T-02.1.1.2, T-05.2.1.1

**T-02.1.1.4 — Login endpoint with rate limiting and audit** · `P0` · `1 pts` · deps: `T-02.1.1.3`
- **Description:** Expose the login endpoint in `interfaces`, apply per-username and per-source-IP
  rate limiting backed by Redis, update `vms.users.last_login_at` on success, and emit
  authentication-success and authentication-failure audit events to the EPIC-05 audit writer.
- **Acceptance Criteria:** Repeated failed attempts beyond the configured threshold are throttled
  with a 429; `last_login_at` updates only on success; both audit events are written with actor,
  outcome and source IP; the audit record contains no password material.
- **Dependencies:** T-02.1.1.3, T-05.1.1.2

#### US-02.1.2 — Refresh, logout and server-side session revocation

**As a** Master Admin **I want** sessions to be refreshable and revocable server-side **so that** a
compromised or ended session cannot continue to be used until its token happens to expire.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-ADM-02 (SRS B1); NFR-SEC-01 (SRS B1); TDD §7 |
| **Dependencies** | US-02.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** a valid refresh token, **When** it is exchanged, **Then** a new access token and a
  new refresh token are issued, and the presented refresh token is immediately invalidated
  (single-use rotation).
- **AC-2 — Given** a logout request, **When** it succeeds, **Then** the session record is removed
  from Redis, the refresh token is invalidated, and any subsequent use of either token is rejected.
- **AC-3 — Given** an administrator deactivates a user, **When** that user's next request is made,
  **Then** it is rejected even though the access token has not expired, because the session store is
  consulted for revocation.
- **AC-4 — Given** NFR-SCL-01's 120 concurrent reception users (SRS B1), **When** 120 sessions are
  active simultaneously, **Then** session lookup remains within the agreed latency budget and no
  session is evicted by capacity.
- **AC-5 (negative) — Given** a refresh token that has already been used once, **When** it is
  presented again, **Then** it is rejected, the entire session family is revoked as a suspected token
  replay, and a security audit event is written.

**Development Tasks**

**T-02.1.2.1 — Redis-backed session store with revocation** · `P0` · `2 pts` · deps: `T-02.1.1.3`
- **Description:** Implement a session store in Redis keyed by session id with the user id, role,
  issue time and expiry, a TTL matching the refresh lifetime, and a revocation check consulted by the
  token validation filter. Key naming must not embed personal data.
- **Acceptance Criteria:** A revoked session's access token is rejected before its expiry; sessions
  expire automatically at TTL; 120 concurrent sessions are held without eviction; keys contain no
  username or email.
- **Dependencies:** T-02.1.1.3

**T-02.1.2.2 — Refresh token rotation with replay detection** · `P0` · `2 pts` · deps: `T-02.1.2.1`
- **Description:** Implement single-use refresh token rotation with a stored hash of the current
  token per session family, and detection logic that revokes the whole family on presentation of a
  previously used token.
- **Acceptance Criteria:** Refresh returns a new pair and invalidates the old; reuse of a consumed
  token revokes the family and returns 401; refresh tokens are stored hashed, never in plaintext.
- **Dependencies:** T-02.1.2.1

**T-02.1.2.3 — Logout, user-deactivation revocation hook and audit** · `P0` · `1 pts` · deps: `T-02.1.2.2`
- **Description:** Expose the logout endpoint, subscribe session revocation to the user-deactivation
  domain event so deactivating a user terminates their live sessions, and emit audit events for
  logout, revocation and replay detection.
- **Acceptance Criteria:** Logout invalidates both tokens; deactivating a user ends their sessions
  within the agreed propagation window; each of the three events appears in `vms.audit_logs`.
- **Dependencies:** T-02.1.2.2, T-02.2.1.3

---

### F-02.2 — User provisioning & administration at 120+1 scale

Create, update, deactivate and assign users across the 120 floor receptions plus central admin.

`SRS` FR-ADM-02 (SRS B1) · TODO-08 · P0 · 8 pts · 2 stories · 6 tasks

#### US-02.2.1 — Administer user accounts

**As a** System Administrator **I want** to create, update, deactivate and reactivate user accounts
with a role and a reception or tenant assignment **so that** the right people can access VMS and
departures can be revoked promptly.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-ADM-02 (SRS B1); NFR-SEC-01 (SRS B1); schema `vms.users` |
| **Dependencies** | US-02.1.1, US-03.2.1 |
| **Blocked by** | TODO-08 — whether accounts are named, shared per floor, or a concurrency ceiling. Named accounts are built; the model is not hard-coded into validation rules that a different disposition would have to unpick. |

**Acceptance Criteria**
- **AC-1 — Given** I hold `user.manage`, **When** I create a user with username, email, full name,
  role and an optional `reception_id` or `tenant_id`, **Then** the row is written to `vms.users` with
  `is_active` true, no password set, and an activation path issued out of band.
- **AC-2 — Given** an existing user, **When** I update their role, reception or tenant assignment,
  **Then** the change is persisted and an audit event records the before-state and after-state.
- **AC-3 — Given** an active user, **When** I deactivate them, **Then** `is_active` becomes false,
  their live sessions are revoked, and the row is retained rather than deleted so historical
  references from `vms.audit_logs` and later workflow tables stay resolvable.
- **AC-4 — Given** a paginated user list, **When** 121 or more users exist, **Then** results are
  paginated, filterable by role, reception and active status, and sortable, without loading all rows.
- **AC-5 (negative) — Given** a username or email that already exists (case-insensitively, per
  `citext`), **When** I attempt to create the user, **Then** the request is rejected with a
  validation error naming the conflicting field and no partial row is written.
- **AC-6 (negative) — Given** I do not hold `user.manage`, **When** I call any user administration
  endpoint, **Then** the request is denied with 403 and an authorization-denial audit event.

**Development Tasks**

**T-02.2.1.1 — User administration use cases and validation** · `P0` · `2 pts` · deps: `T-02.1.1.1`
- **Description:** Implement create, update, deactivate and reactivate use cases in the `identity`
  application layer, with validation on username and email format and uniqueness, mandatory role
  reference, and a rule that a user assigned `reception_id` must reference an active row in
  `vms.receptions` and a user assigned `tenant_id` an active `vms.tenants` row.
- **Acceptance Criteria:** Each use case is unit tested including its failure paths; duplicate
  username or email is rejected case-insensitively; an inactive reception or tenant reference is
  rejected; no use case accepts or returns a password hash.
- **Dependencies:** T-02.1.1.1

**T-02.2.1.2 — User administration API with authorization and audit** · `P0` · `2 pts` · deps: `T-02.2.1.1`
- **Description:** Expose the administration endpoints in `interfaces` guarded by the `user.manage`
  permission, with server-side pagination, filtering and sorting, and audit events on every state
  change carrying before-state and after-state with the password hash excluded from both.
- **Acceptance Criteria:** Endpoints enforce `user.manage` and return 403 otherwise; listing 121
  users paginates; every mutation writes an audit row; no audit payload contains `password_hash`.
- **Dependencies:** T-02.2.1.1, T-03.2.1.2

**T-02.2.1.3 — User deactivation domain event** · `P1` · `1 pts` · deps: `T-02.2.1.1`
- **Description:** Publish a `UserDeactivated` domain event to Kafka on deactivation so the session
  store (US-02.1.2) can revoke live sessions without the identity context reaching into Redis
  directly.
- **Acceptance Criteria:** Deactivation publishes exactly one event carrying the user id and no
  personal data; the event is consumed idempotently; a redelivered event causes no error.
- **Dependencies:** T-02.2.1.1

#### US-02.2.2 — Provision reception accounts at scale

**As a** System Administrator **I want** to provision the floor-reception user population in bulk
from a reviewed input file **so that** onboarding 120 receptions is not 120 manual form submissions.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-ADM-02 (SRS B1) — "120 floor-reception user logins plus 1 central admin login" |
| **Dependencies** | US-02.2.1, US-04.4.1 |
| **Blocked by** | TODO-08 — the intended account model is unresolved. This story delivers **import mechanics only**: it does not encode a per-floor account-count rule, does not enforce a licence ceiling, and does not create shared accounts. The account-model rule is added once TODO-08 is dispositioned. |

**Acceptance Criteria**
- **AC-1 — Given** a validated import file of user records, **When** I submit it, **Then** each row
  is validated before anything is written and a preview report shows rows that would be created,
  skipped or rejected with reasons.
- **AC-2 — Given** I confirm a validated preview, **When** the import runs, **Then** users are
  created in a single transaction per batch, each linked to an existing active reception, and one
  audit event per created user is written plus one summary audit event for the import.
- **AC-3 — Given** an import containing a username that already exists, **When** the preview runs,
  **Then** that row is reported as a conflict and the import proceeds only for the remaining rows
  after explicit confirmation.
- **AC-4 (negative) — Given** an import file containing a password column, **When** it is submitted,
  **Then** the file is rejected outright: passwords are never accepted through import, and activation
  is always out of band.
- **AC-5 (negative) — Given** an import referencing a `reception_id` that does not exist or is
  inactive, **When** validation runs, **Then** that row is rejected with a specific reason and no
  user is created for it.

**Development Tasks**

**T-02.2.2.1 — Import parsing, validation and dry-run preview** · `P1` · `1 pts` · deps: `T-02.2.1.1`
- **Description:** Implement parsing of the agreed import format into candidate user records, full
  validation against the same rules as single-user creation, and a dry-run producing a per-row
  outcome report with no writes.
- **Acceptance Criteria:** A valid file previews all rows as creatable; conflicts, unknown receptions
  and malformed rows are each reported with a distinct reason; a file containing a password column is
  rejected before parsing completes; dry-run writes nothing.
- **Dependencies:** T-02.2.1.1

**T-02.2.2.2 — Transactional batch creation with per-user audit** · `P1` · `1 pts` · deps: `T-02.2.2.1`
- **Description:** Implement confirmed batch execution in bounded transactions with per-user audit
  events and a summary event recording the actor, the file identity (name and content hash, not
  contents) and the outcome counts.
- **Acceptance Criteria:** A failure mid-batch rolls back that batch without partial users; audit
  rows exist per created user plus one summary; the summary records a hash, not the file contents.
- **Dependencies:** T-02.2.2.2

**T-02.2.2.3 — Out-of-band activation flow** · `P1` · `1 pts` · deps: `T-02.2.2.2`
- **Description:** Implement single-use, time-limited activation tokens stored hashed, redeemed to
  set an initial password subject to the F-02.3 policy, with no password ever transmitted by an
  administrator. Delivery is a stub in this phase; the email channel arrives in Phase 4 (F-16.2).
- **Acceptance Criteria:** A token activates exactly once and expires at its TTL; a redeemed or
  expired token is rejected; tokens are stored hashed; no activation token appears in any log.
- **Dependencies:** T-02.2.2.2, T-02.3.1.1

---

### F-02.3 — Account security policy — password, lockout, rotation

Password strength, hashing, lockout and rotation rules applied consistently to every account.

`SRS-NFR` NFR-SEC-01 (SRS B1) · P0 · 5 pts · 1 story · 3 tasks

#### US-02.3.1 — Enforce account security policy

**As a** System Administrator **I want** password strength, secure hashing, lockout and rotation
enforced centrally **so that** a weak or brute-forced credential cannot become the way into visitor
personal data.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS-NFR` NFR-SEC-01 (SRS B1); OWASP ASVS V2; schema `vms.users.password_hash` |
| **Dependencies** | US-02.1.1 |
| **Blocked by** | TODO-15 — if client SSO or AD federation is mandated, password policy moves to the identity provider. Policy is implemented behind the authentication-provider port so it can be disabled wholesale rather than unpicked. |

**Acceptance Criteria**
- **AC-1 — Given** a new or changed password, **When** it is submitted, **Then** it is validated
  against the configured minimum length and a breached-password denylist, and rejected with guidance
  that does not reveal which specific rule matched a denylist entry.
- **AC-2 — Given** an accepted password, **When** it is stored, **Then** only an Argon2id (or agreed
  bcrypt cost) hash is written to `vms.users.password_hash`, with a per-user salt, and the plaintext
  is not retained in any field, log, cache or exception message.
- **AC-3 — Given** consecutive failed logins for one account beyond the configured threshold,
  **When** the threshold is crossed, **Then** the account is temporarily locked for the configured
  window, further attempts are rejected identically to a wrong password, and a lockout audit event is
  written.
- **AC-4 — Given** a self-service password change, **When** it succeeds, **Then** the current
  password must have been supplied, all other sessions for that user are revoked, and a password
  change audit event is written.
- **AC-5 (negative) — Given** an attacker enumerating usernames through the lockout behaviour,
  **When** they attempt logins against a non-existent account, **Then** responses are
  indistinguishable from those for a locked or wrong-password existing account.

**Development Tasks**

**T-02.3.1.1 — Password policy and hashing service** · `P0` · `2 pts` · deps: `T-02.1.1.2`
- **Description:** Implement a password policy component with configurable minimum length, a breached
  password denylist check, and an Argon2id hashing service with agreed parameters and a versioned
  hash prefix supporting future rehashing on login.
- **Acceptance Criteria:** Policy rejects short and denylisted passwords; hashes verify correctly and
  carry a version marker; plaintext never leaves the method scope; no test fixture contains a real
  reused password.
- **Dependencies:** T-02.1.1.2

**T-02.3.1.2 — Account lockout with enumeration-resistant responses** · `P0` · `2 pts` · deps: `T-02.3.1.1`
- **Description:** Implement failed-attempt counting per account in Redis with a configurable
  threshold and lock window, automatic expiry of the lock, an administrator unlock action gated on
  `user.manage`, and response shaping so locked, wrong-password and unknown-account outcomes are
  indistinguishable to the caller.
- **Acceptance Criteria:** Threshold crossing locks the account; the lock expires automatically;
  administrator unlock works and is audited; a test asserts identical response body, status and
  timing envelope across the three failure cases.
- **Dependencies:** T-02.3.1.1, T-02.1.2.1

**T-02.3.1.3 — Password change and rotation with session revocation** · `P1` · `1 pts` · deps: `T-02.3.1.2`
- **Description:** Implement self-service password change requiring the current password, revocation
  of all other sessions on success, and a configurable rotation interval that flags an account as
  requiring a change at next login. Administrator-initiated reset issues an activation token rather
  than setting a password.
- **Acceptance Criteria:** Change without the correct current password is rejected; other sessions
  are revoked on success; an account past the rotation interval is forced to change before any other
  endpoint is reachable; an administrator cannot set a password value directly.
- **Dependencies:** T-02.3.1.2, T-02.1.2.1

---

### F-02.4 — Master Admin authority

The single central-reception authority defined by FR-ADM-01 (SRS B1).

`SRS` FR-ADM-01 (SRS B1) · P0 · 3 pts · 1 story · 3 tasks

#### US-02.4.1 — Establish and protect Master Admin authority

**As a** Master Admin **I want** my central-reception authority to be a distinct, protected role
**so that** the authority to approve visitor access and initiate credential requests is
unambiguously held and cannot be silently removed or duplicated.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS` FR-ADM-01 (SRS B1) — "one Master Admin at central reception with authority to approve visitor access and initiate credential requests" |
| **Dependencies** | US-02.2.1, US-03.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the seeded `MASTER_ADMIN` role, **When** I inspect its grants, **Then** it holds
  `visitor.approve` and `credential.issue` — the two authorities FR-ADM-01 (SRS B1) names — in
  addition to its operational permissions.
- **AC-2 — Given** the system has been provisioned, **When** I query active users holding
  `MASTER_ADMIN`, **Then** the count is reported on the administration screen, and creating an
  additional one requires an explicit confirmation that records a justification in the audit event.
- **AC-3 — Given** a Master Admin account, **When** it is assigned a reception, **Then** validation
  requires that reception's `is_central` flag to be true in `vms.receptions`.
- **AC-4 (negative) — Given** exactly one active Master Admin exists, **When** an administrator
  attempts to deactivate or downgrade that account, **Then** the operation is refused with an
  explanatory error, because it would leave the system with no holder of FR-ADM-01 authority.
- **AC-5 (negative) — Given** a first-run system with no users, **When** the bootstrap admin is
  created, **Then** it is created through an explicit one-time operator-run command requiring an
  externally supplied password that is never defaulted, never seeded in a migration, and never
  logged.

**Development Tasks**

**T-02.4.1.1 — Master Admin role grants and central-reception constraint** · `P0` · `1 pts` · deps: `T-03.1.1.2`
- **Description:** Confirm and, where missing, add the `MASTER_ADMIN` permission grants in the
  reference seed, and implement validation that a `MASTER_ADMIN` user's `reception_id` must reference
  a `vms.receptions` row with `is_central` true.
- **Acceptance Criteria:** Grants include `visitor.approve` and `credential.issue`; assigning a
  non-central reception is rejected with a specific error; the seed remains idempotent.
- **Dependencies:** T-03.1.1.2, T-02.2.1.1

**T-02.4.1.2 — Last-Master-Admin protection and duplicate confirmation** · `P0` · `1 pts` · deps: `T-02.4.1.1`
- **Description:** Implement a guard preventing deactivation or role change of the final active
  `MASTER_ADMIN`, and a confirmation-with-justification path for creating an additional one, with the
  justification recorded in the audit event. Apply the check under a serialised transaction so two
  concurrent deactivations cannot both pass.
- **Acceptance Criteria:** Deactivating the sole Master Admin is refused; two concurrent deactivation
  attempts on the last two Master Admins leave at least one active; the justification appears in the
  audit event.
- **Dependencies:** T-02.4.1.1

**T-02.4.1.3 — First-run bootstrap administrator command** · `P0` · `1 pts` · deps: `T-02.3.1.1`
- **Description:** Implement a one-time operator-run bootstrap command that creates the initial
  `SYSTEM_ADMIN` account only when no user exists, taking the password from an operator-supplied
  input, applying the F-02.3 policy, and refusing to run once any user is present.
- **Acceptance Criteria:** The command succeeds exactly once on an empty `vms.users` and refuses
  thereafter; no default password exists anywhere in source, configuration or migrations; the
  supplied password appears in no log or shell history artefact written by the application.
- **Dependencies:** T-02.3.1.1, T-01.5.1.3

---

## EPIC-03 — Authorization & RBAC

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` / `SRS-NFR` / `BLOCKED` |
| **Requirement IDs** | NFR-SEC-01 (SRS B1); FR-USR-02 *(TDD/schema reference — undefined in SRS B1)* |
| **Priority** | P0 |
| **Features** | 4 · **Stories** 5 · **Tasks** 16 · **Points** 21 |
| **Open questions** | TODO-01 (F-03.1, F-03.3 rest on FR-USR-02, which has no SRS definition), TODO-14 (tenant and floor data isolation) |

**Goal.** Turn NFR-SEC-01's requirement that "visitor personal data shall be access-controlled by
user role within VMS" into a mechanism: a role-permission model over `vms.roles`,
`vms.permissions` and `vms.role_permissions`, enforced deny-by-default at the API boundary, and
administrable by a System Administrator. The enforcement feature (F-03.2) is squarely `SRS-NFR` and
is authorized. The model and administration features cite FR-USR-02, which the TDD and schema
reference but SRS B1 does not define — they are `TDD-DERIVED` and carry the TODO-01 caveat, though
the seed data in schema §14 already fixes the five roles and eleven permissions, which limits how
much interpretation is being invented. F-03.4 is `BLOCKED`: no source document states whether Tenant
A may see Tenant B's data or whether a floor receptionist sees only their floor, and retrofitting
that decision later is expensive — so this phase builds the *seam* and none of the policy.

> **Deny-by-default is the governing rule.** An endpoint with no explicit permission declaration is
> unreachable, not public. This is asserted by a test that enumerates every mapped route.

---

### F-03.1 — Role & permission model

The RBAC aggregate over the seeded roles and permissions, and the resolution of a user's effective
permission set.

`TDD-DERIVED` FR-USR-02 *(TDD §4.6, §7; schema §4 — no SRS B1 definition)* · P0 · 5 pts · 1 story · 4 tasks

#### US-03.1.1 — Role and permission model with effective-permission resolution

**As a** System Administrator **I want** roles to carry permission grants and a user's effective
permissions to be resolved from their role **so that** access decisions are made against permissions
rather than hard-coded role names scattered through the code.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-USR-02 *(TDD §4.6; schema `vms.roles`, `vms.permissions`, `vms.role_permissions`)*; justified against NFR-SEC-01 (SRS B1) |
| **Dependencies** | US-01.5.1, US-02.1.1 |
| **Blocked by** | TODO-01 — FR-USR-02 is referenced by the TDD and schema but undefined in SRS B1. The model is constrained to exactly the five roles and eleven permissions already seeded in schema §14; no additional role or permission is invented. |

**Acceptance Criteria**
- **AC-1 — Given** the baseline seed, **When** I query `vms.roles`, **Then** exactly the five seeded
  roles exist — `MASTER_ADMIN`, `FLOOR_RECEPTIONIST`, `FM_ADMIN`, `TENANT`, `SYSTEM_ADMIN` — and
  `vms.permissions` holds exactly the eleven seeded permission codes.
- **AC-2 — Given** the role-permission grants, **When** I resolve a user's effective permissions,
  **Then** the result is the set of permission codes granted to their `role_id` via
  `vms.role_permissions`, and an inactive user resolves to the empty set.
- **AC-3 — Given** effective permissions are resolved on every request, **When** the same user makes
  repeated requests, **Then** the permission set is cached in Redis with a bounded TTL and the cache
  is invalidated immediately when the user's role or a role's grants change.
- **AC-4 — Given** a permission code, **When** it is referenced in code, **Then** it is referenced
  through a single generated or curated constant set, so a typo is a compile error rather than a
  silent permanent denial.
- **AC-5 (negative) — Given** a user whose `role_id` references a role with no grants, **When** they
  call any protected endpoint, **Then** every call is denied, confirming the model fails closed
  rather than defaulting to permissive.

**Development Tasks**

**T-03.1.1.1 — RBAC domain model and repository** · `P0` · `1 pts` · deps: `T-02.1.1.1`
- **Description:** Model `Role`, `Permission` and `PermissionSet` in the `identity` domain context
  with no framework annotations, and implement the persistence adapter reading `vms.roles`,
  `vms.permissions` and `vms.role_permissions`.
- **Acceptance Criteria:** Domain types carry no framework import; grants load correctly for each
  seeded role; a role with no grants yields an empty set rather than null.
- **Dependencies:** T-02.1.1.1

**T-03.1.1.2 — Role-permission grant seed for the five baseline roles** · `P0` · `2 pts` · deps: `T-03.1.1.1`
- **Description:** Author the idempotent migration populating `vms.role_permissions` for the five
  seeded roles against the eleven seeded permission codes, granting each role the minimum set implied
  by its schema §14 description — for example `TENANT` receives `visitor.request` only,
  `FLOOR_RECEPTIONIST` receives `visitor.register` and `masterdata.view`, `FM_ADMIN` receives
  `visitor.approve`, `MASTER_ADMIN` receives `visitor.approve` and `credential.issue`, and
  `SYSTEM_ADMIN` receives `user.manage`, `masterdata.edit` and `settings.manage`. Grant no permission
  that the role's description does not support; `credential.override` is granted to no role in this
  phase, pending TODO-04.
- **Acceptance Criteria:** Re-running the migration does not duplicate grants; each role's grant set
  is documented in the migration comment with its justification; `credential.override` has zero
  grants; a test asserts the exact grant matrix.
- **Dependencies:** T-03.1.1.1, T-01.5.1.3

**T-03.1.1.3 — Effective-permission resolution with cached lookup** · `P0` · `1 pts` · deps: `T-03.1.1.2`
- **Description:** Implement the use case resolving a principal's effective permission set, backed by
  a Redis cache with a bounded TTL, returning an empty set for an inactive user.
- **Acceptance Criteria:** Resolution returns the correct set per seeded role; an inactive user
  resolves empty; a cache hit avoids the database round trip; cache keys carry no personal data.
- **Dependencies:** T-03.1.1.2

**T-03.1.1.4 — Permission constants and cache invalidation on grant change** · `P0` · `1 pts` · deps: `T-03.1.1.3`
- **Description:** Provide a single curated constant set for the eleven permission codes, verified
  against the database by a test, and invalidate the effective-permission cache on any role-grant
  change or user role reassignment.
- **Acceptance Criteria:** A test fails if a constant has no matching `vms.permissions` row or a row
  has no constant; changing a role's grants takes effect on the next request without a restart;
  changing a user's role invalidates only that user's entry.
- **Dependencies:** T-03.1.1.3

---

### F-03.2 — API-boundary authorization enforcement

Deny-by-default enforcement on every endpoint, with denials handled safely and audited.

`SRS-NFR` NFR-SEC-01 (SRS B1) · P0 · 8 pts · 2 stories · 6 tasks

#### US-03.2.1 — Deny-by-default authorization at the API boundary

**As a** System Administrator **I want** every API endpoint to require an explicitly declared
permission **so that** visitor personal data is access-controlled by role as NFR-SEC-01 requires,
and a newly added endpoint cannot accidentally be public.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS-NFR` NFR-SEC-01 (SRS B1); OWASP ASVS V4; TDD §7 |
| **Dependencies** | US-03.1.1, US-02.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the security configuration, **When** any request arrives at any path other than
  the explicitly listed public paths (login, refresh, health, metrics on the management port),
  **Then** authentication is required before any handler executes.
- **AC-2 — Given** an authenticated principal, **When** they call an endpoint declaring a required
  permission, **Then** access is granted only if that permission is in their effective set, and the
  decision is made before the handler and before any database read of the target resource.
- **AC-3 — Given** the full set of mapped routes, **When** the authorization coverage test runs,
  **Then** it fails if any route has neither a permission declaration nor an entry in the reviewed
  public-path allowlist.
- **AC-4 — Given** a request whose body or query attempts to change the acting user, **When** it is
  processed, **Then** the principal is taken solely from the validated token and never from
  client-supplied input.
- **AC-5 (negative) — Given** a valid token for a `TENANT` user, **When** they call a user
  administration endpoint requiring `user.manage`, **Then** the response is 403, no partial work is
  performed, and no data about the target resource is disclosed in the response.
- **AC-6 (negative) — Given** an expired or revoked session, **When** a request is made with its
  still-unexpired access token, **Then** the response is 401 rather than 403, and the session store
  revocation check is what produced it.

**Development Tasks**

**T-03.2.1.1 — Security filter chain with default-deny configuration** · `P0` · `2 pts` · deps: `T-02.1.1.3`
- **Description:** Configure the Spring Security filter chain so all requests require authentication
  by default, with a short reviewed allowlist of public paths, stateless session management, CSRF
  handling appropriate to the token-based model, and secure defaults for CORS restricted to the
  portal origin per environment.
- **Acceptance Criteria:** An unauthenticated call to any non-allowlisted path returns 401; the
  allowlist is a single reviewable declaration; CORS rejects an unknown origin; no wildcard origin is
  configured in any profile.
- **Dependencies:** T-02.1.1.3

**T-03.2.1.2 — Permission-based method and route guards** · `P0` · `2 pts` · deps: `T-03.2.1.1`
- **Description:** Implement the permission evaluation integration so an endpoint declares its
  required permission code, resolved against the cached effective-permission set, evaluated before
  the handler body and before any resource fetch. Ensure the principal is derived only from the
  validated token.
- **Acceptance Criteria:** An endpoint declaring `user.manage` is reachable only by a principal
  holding it; a client-supplied user identifier in the body cannot alter the acting principal; the
  guard executes before the handler, verified by a test asserting no repository call occurs on denial.
- **Dependencies:** T-03.2.1.1, T-03.1.1.4

**T-03.2.1.3 — Route authorization coverage test** · `P0` · `1 pts` · deps: `T-03.2.1.2`
- **Description:** Implement a test that enumerates every mapped route from the request-mapping
  handler registry and asserts each is either permission-guarded or present in the public allowlist,
  failing with the offending route when neither holds.
- **Acceptance Criteria:** The test passes on the current route set; adding an unguarded endpoint
  fails the build naming the route and method; the test runs in the standard CI test job.
- **Dependencies:** T-03.2.1.2

#### US-03.2.2 — Safe denial handling and authorization audit

**As a** System Administrator **I want** authorization failures to return a uniform, non-disclosing
response and to be recorded **so that** a probing attacker learns nothing and a genuine access
problem is diagnosable after the fact.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 3 |
| **Provenance** | `SRS-NFR` NFR-SEC-01 (SRS B1); OWASP ASVS V4, V7 |
| **Dependencies** | US-03.2.1, US-05.1.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** any authorization denial, **When** the response is produced, **Then** it is a
  uniform problem-detail body carrying the correlation id and a generic message, with no stack trace,
  no internal type name, no SQL and no indication of whether the target resource exists.
- **AC-2 — Given** an authorization denial, **When** it occurs, **Then** an audit event is written
  recording the actor (or "anonymous"), the attempted permission, the route, the outcome and the
  source IP — and no request body content.
- **AC-3 — Given** repeated denials for one principal beyond a configured threshold, **When** the
  threshold is crossed, **Then** a system alert metric is incremented so the condition is observable.
- **AC-4 (negative) — Given** an unhandled exception inside a handler, **When** the response is
  produced, **Then** it is a generic 500 problem-detail with the correlation id only, and the
  exception detail appears solely in the server log with all PII fields redacted.
- **AC-5 (negative) — Given** a denial for a request carrying a visitor email in its body, **When**
  the audit event and the log line are written, **Then** neither contains that email.

**Development Tasks**

**T-03.2.2.1 — Uniform problem-detail error handling** · `P0` · `1 pts` · deps: `T-03.2.1.2`
- **Description:** Implement a global exception handler producing RFC 7807 problem-detail responses
  for 400, 401, 403, 404, 409 and 500, with a fixed shape, the correlation id, and no internal
  detail. Ensure 403 and 404 for an existent versus non-existent resource are indistinguishable where
  the caller lacks read authority.
- **Acceptance Criteria:** Each status returns the uniform shape; no response body contains a stack
  trace, class name or SQL fragment; a test asserts a denied request for an existing resource and for
  a non-existent one produce identical bodies.
- **Dependencies:** T-03.2.1.2

**T-03.2.2.2 — Authorization denial audit events** · `P0` · `1 pts` · deps: `T-03.2.2.1`
- **Description:** Emit an audit event on every authentication and authorization denial through the
  EPIC-05 audit writer, carrying actor, attempted permission, route, method, outcome and source IP,
  and explicitly excluding request and response bodies.
- **Acceptance Criteria:** A denied request produces exactly one `vms.audit_logs` row; the row
  contains no body content and no PII; an anonymous denial records "anonymous" rather than failing.
- **Dependencies:** T-03.2.2.1, T-05.1.1.2

**T-03.2.2.3 — Denial-rate metric and alert threshold** · `P2` · `1 pts` · deps: `T-03.2.2.2`
- **Description:** Add a counter metric for denials dimensioned by outcome and route class (not by
  user identity), and a configurable threshold that marks the condition as alertable through the
  F-01.7 metrics pipeline.
- **Acceptance Criteria:** The metric increments on denial; its labels carry no user identifier or
  personal data; crossing the threshold is observable on the metrics endpoint.
- **Dependencies:** T-03.2.2.2, T-01.7.1.3

---

### F-03.3 — Role & permission administration

Administer role definitions and their permission grants without a code deployment.

`TDD-DERIVED` FR-USR-02 *(TDD §4.6, §7; schema §4 — no SRS B1 definition)* · P1 · 5 pts · 1 story · 3 tasks

#### US-03.3.1 — Administer roles and permission grants

**As a** System Administrator **I want** to view roles, view the permission catalogue, and adjust
which permissions a role grants **so that** access can be corrected without a release.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-USR-02 *(TDD §4.6; schema `vms.role_permissions`)*; justified against NFR-SEC-01 (SRS B1) |
| **Dependencies** | US-03.1.1, US-03.2.1, US-05.1.1 |
| **Blocked by** | TODO-01 — FR-USR-02 is undefined in SRS B1. Scope is deliberately limited to **editing grants on the five seeded roles**; creating arbitrary new roles or permission codes is out of scope until TODO-01 is dispositioned. |

**Acceptance Criteria**
- **AC-1 — Given** I hold `user.manage`, **When** I view the roles screen, **Then** I see the five
  roles, each role's current grants, the eleven-permission catalogue with descriptions, and the count
  of active users holding each role.
- **AC-2 — Given** I grant or revoke a permission on a role, **When** I save, **Then**
  `vms.role_permissions` is updated, the effective-permission cache is invalidated for every affected
  user, and an audit event records the before-state and after-state grant sets.
- **AC-3 — Given** an affected user has a live session, **When** their next request is handled after
  a grant change, **Then** the new grants apply without requiring them to log in again.
- **AC-4 (negative) — Given** I attempt to revoke `user.manage` from `SYSTEM_ADMIN` when doing so
  would leave no active user holding `user.manage`, **When** I save, **Then** the change is refused
  with an explanatory error, preventing an administrative lockout.
- **AC-5 (negative) — Given** two administrators load the same role and both submit changes, **When**
  the second saves against a stale version, **Then** the save is rejected with a conflict and the
  second administrator is shown the current state rather than silently overwriting.
- **AC-6 (negative) — Given** I do not hold `user.manage`, **When** I call any role administration
  endpoint, **Then** the request is denied with 403 and audited.

**Development Tasks**

**T-03.3.1.1 — Role and grant administration use cases with lockout guard** · `P1` · `2 pts` · deps: `T-03.1.1.4`
- **Description:** Implement read and grant-modification use cases over `vms.roles` and
  `vms.role_permissions`, restricted to the seeded roles, with a guard refusing any change that would
  leave zero active users holding `user.manage`, evaluated inside the same transaction as the change.
- **Acceptance Criteria:** Grants can be added and removed; role creation and permission-code
  creation are not exposed; the lockout guard refuses the offending change; the guard is correct
  under two concurrent transactions.
- **Dependencies:** T-03.1.1.4

**T-03.3.1.2 — Optimistic concurrency on grant edits** · `P1` · `1 pts` · deps: `T-03.3.1.1`
- **Description:** Introduce a version token for a role's grant set, returned on read and required on
  write, rejecting a stale write with a conflict response that includes the current state.
- **Acceptance Criteria:** A save with a current version succeeds; a save with a stale version
  returns 409 and does not modify grants; the conflict response carries the current grant set.
- **Dependencies:** T-03.3.1.1

**T-03.3.1.3 — Administration API with cache invalidation and audit** · `P1` · `2 pts` · deps: `T-03.3.1.2`
- **Description:** Expose the role administration endpoints guarded by `user.manage`, invalidate the
  effective-permission cache for all users holding the affected role, and write an audit event
  carrying before-state and after-state grant sets.
- **Acceptance Criteria:** Endpoints enforce `user.manage`; a grant change takes effect for a live
  session on its next request; each change writes exactly one audit row with both states populated.
- **Dependencies:** T-03.3.1.2, T-05.1.1.2

---

### F-03.4 — Tenant & floor data scoping

The seam for tenant and floor data isolation — mechanism only, no policy.

`BLOCKED` TODO-14 · P1 · 3 pts · 1 story · 3 tasks

#### US-03.4.1 — Data scoping seam for tenant and floor isolation

**As a** System Administrator **I want** every query path for scoped data to run through a single
scoping component **so that** the tenant and floor isolation rules can be applied in one place once
they are decided, rather than retrofitted across the codebase.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `BLOCKED` TODO-14; mechanism justified against NFR-SEC-01 (SRS B1) |
| **Dependencies** | US-03.1.1, US-03.2.1 |
| **Blocked by** | TODO-14 — no source document states whether Tenant A may see Tenant B's visitor data, or whether a floor receptionist is limited to their floor. **This story builds the seam and a deny-all default; it does not decide the isolation model.** No permissive scoping rule may be written until TODO-14 is dispositioned. |

**Acceptance Criteria**
- **AC-1 — Given** the authenticated principal, **When** a request is handled, **Then** a scope
  context is populated from the token and the user's `tenant_id` and `reception_id` in `vms.users`,
  and is available to the application layer without being passed through every method signature.
- **AC-2 — Given** a repository method reading scoped data, **When** it executes, **Then** it obtains
  its scope predicate from the single scoping component rather than constructing one inline.
- **AC-3 — Given** the scoping policy is undecided, **When** the default policy is consulted,
  **Then** it returns a **deny-all** predicate for any entity registered as scoped, so an
  unconsidered query returns nothing rather than everything.
- **AC-4 — Given** a documented ADR marked provisional, **When** the seam is reviewed, **Then** the
  ADR records that no isolation policy has been chosen, names TODO-14, and lists the entity types
  registered as scope-sensitive (`vms.visitor_requests`, `vms.visitors`, `vms.hosts`, and the
  `vms.users` list view) for Phase 2 to consume.
- **AC-5 (negative) — Given** a developer adds a query over a scope-sensitive entity that bypasses
  the scoping component, **When** the architecture fitness suite runs, **Then** the build fails
  naming the query.

**Development Tasks**

**T-03.4.1.1 — Request-scoped scope context** · `P1` · `1 pts` · deps: `T-03.2.1.2`
- **Description:** Implement a request-scoped scope context populated from the validated principal
  with the user id, role code, `tenant_id` and `reception_id`, cleared at request end, and available
  to the application layer through a port rather than a framework-specific holder.
- **Acceptance Criteria:** The context is populated for every authenticated request; it is empty for
  allowlisted public paths; a pooled thread carries no residual context between requests.
- **Dependencies:** T-03.2.1.2

**T-03.4.1.2 — Scoping component with deny-all default policy** · `P1` · `1 pts` · deps: `T-03.4.1.1`
- **Description:** Implement the single component producing a scope predicate per registered
  scope-sensitive entity type, with the only shipped policy being deny-all, and register the entity
  types Phase 2 will need. Record the provisional ADR naming TODO-14.
- **Acceptance Criteria:** The component returns a deny-all predicate for every registered type; no
  permissive policy exists in the codebase; the ADR is committed and marked provisional.
- **Dependencies:** T-03.4.1.1

**T-03.4.1.3 — Fitness test forbidding scoping bypass** · `P1` · `1 pts` · deps: `T-03.4.1.2`
- **Description:** Add an architecture fitness rule asserting that every repository method returning
  a scope-sensitive entity type obtains its predicate from the scoping component.
- **Acceptance Criteria:** The rule passes on the current codebase; a deliberately added bypassing
  query fails the build naming the method; the rule runs in the architecture CI job.
- **Dependencies:** T-03.4.1.2, T-01.2.2.3

---

## EPIC-04 — Configuration & Master Data

> ## ⚠️ THIS ENTIRE EPIC IS `TDD-DERIVED` — BACKLOG ONLY, PENDING TODO-01
>
> Every feature in EPIC-04 cites an `FR-CFG-*` or `FR-SET-01` requirement that the **TDD §4.1 and
> the schema reference but SRS B1 does not define**. Per the requirements catalogue §5 and the
> provenance rule in the epic index, none of these stories may enter a sprint until TODO-01 is
> dispositioned — either SRS v2 is supplied, or the client confirms in writing that the attached
> 28-requirement SRS is the delivery baseline and the TDD/schema will be reduced to match.
>
> They are estimated and sequenced here so the plan carries a realistic shape and cost, and because
> Phase 2's visitor workflow has hard foreign keys into `vms.tenants`, `vms.floors`,
> `vms.visitor_types` and `vms.pass_types` — if TODO-01 resolves in favour of building them, they
> are on the critical path. **A TDD-DERIVED story entering a sprint without that disposition is a
> process failure.**
>
> Scope discipline while blocked: each story is constrained to the columns that actually exist in
> `docs/vms_schema_postgresql.sql`. No attribute, workflow, hierarchy or business rule beyond the
> schema is invented. All EPIC-04 endpoints ship behind a feature flag defaulting to **off**.

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` (entire epic) |
| **Requirement IDs** | FR-CFG-02 … FR-CFG-08, FR-SET-01 *(TDD §4.1 / schema §3, §11 — all undefined in SRS B1)* |
| **Priority** | P1 |
| **Features** | 9 · **Stories** 11 · **Tasks** 31 · **Points** 33 |
| **Open questions** | TODO-01 (blocks the whole epic) |

**Goal.** Provide the reference data every later phase depends on: buildings, floors, tenants,
reception points, visitor types, pass types, the holiday calendar and system settings — each with
create, read, update and deactivate operations, referential integrity honouring the schema's
`ON DELETE RESTRICT` and `ON DELETE SET NULL` choices, soft deactivation rather than deletion so
historical references stay resolvable, full audit coverage, and a Redis read-through cache with
event-driven invalidation so reference lookups do not become the bottleneck under NFR-SCL-01's 120
concurrent reception users. The consistent shape across all eight master data types is deliberate:
one shared CRUD pattern, one shared validation approach, one shared cache, one shared audit hook.

---

### F-04.1 — Building master data

Create, read, update and deactivate buildings in `vms.buildings`.

`TDD-DERIVED` FR-CFG-02 *(TDD §4.1; schema `vms.buildings`)* · P1 · 3 pts · 1 story · 3 tasks

#### US-04.1.1 — Manage buildings

**As a** System Administrator **I want** to manage the building register **so that** floors,
receptions and everything hanging off them have a valid parent.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-CFG-02 *(TDD §4.1; schema `vms.buildings`)* |
| **Dependencies** | US-01.5.1, US-03.2.1, US-05.1.1 |
| **Blocked by** | TODO-01 — FR-CFG-02 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** I hold `masterdata.edit`, **When** I create a building with a unique `code`, a
  `name` and an optional `address`, **Then** the row is written to `vms.buildings` with `is_active`
  true and an audit event records the creation.
- **AC-2 — Given** I hold `masterdata.view`, **When** I list buildings, **Then** results are
  paginated, filterable by active status and searchable by code or name.
- **AC-3 — Given** an existing building, **When** I deactivate it, **Then** `is_active` becomes
  false, the row is retained, and it no longer appears in selection lists offered when creating
  dependent records.
- **AC-4 (negative) — Given** a building code that already exists, **When** I create or rename to it,
  **Then** the request is rejected with a validation error naming the field, honouring the schema's
  unique constraint rather than relying on a race-prone pre-check.
- **AC-5 (negative) — Given** a building that has floors, **When** I attempt to delete it, **Then**
  deletion is not offered at all — only deactivation — because `vms.floors.building_id` is
  `ON DELETE RESTRICT` and historical references must stay resolvable.
- **AC-6 (negative) — Given** I hold only `masterdata.view`, **When** I attempt a create or update,
  **Then** the request is denied with 403 and an authorization-denial audit event is written.

**Development Tasks**

**T-04.1.1.1 — Shared master data CRUD pattern and building domain model** · `P1` · `1 pts` · deps: `T-01.5.1.2`
- **Description:** Establish the reusable master data pattern in the `masterdata` context — a base
  use-case shape for create, update, deactivate, reactivate and paged query, a common validation
  approach for `code` and `name`, and a soft-deactivation convention. Apply it first to the
  `Building` aggregate over `vms.buildings`.
- **Acceptance Criteria:** The pattern is documented and unit tested once; `Building` uses it with no
  bespoke logic; domain types carry no framework import; no delete operation exists in the pattern.
- **Dependencies:** T-01.5.1.2, T-01.2.1.2

**T-04.1.1.2 — Building persistence adapter with constraint-driven conflict handling** · `P1` · `1 pts` · deps: `T-04.1.1.1`
- **Description:** Implement the repository adapter over `vms.buildings`, translating the unique
  constraint violation on `code` into a domain conflict result rather than a pre-check, and
  supporting paged, filtered, sorted queries.
- **Acceptance Criteria:** Duplicate `code` yields a conflict result under concurrent inserts;
  pagination does not load the full table; `updated_at` is maintained by the existing schema trigger.
- **Dependencies:** T-04.1.1.1

**T-04.1.1.3 — Building API behind the master data feature flag, guarded and audited** · `P1` · `1 pts` · deps: `T-04.1.1.2`
- **Description:** Expose the building endpoints guarded by `masterdata.view` and `masterdata.edit`,
  behind the `masterdata.enabled` feature flag defaulting to off, emitting audit events with
  before-state and after-state on every mutation.
- **Acceptance Criteria:** With the flag off every endpoint returns 404; with it on, read requires
  `masterdata.view` and mutation requires `masterdata.edit`; each mutation writes one audit row.
- **Dependencies:** T-04.1.1.2, T-03.2.1.2, T-05.1.1.2

---

### F-04.2 — Floor master data

Floors within a building, in `vms.floors`.

`TDD-DERIVED` FR-CFG-03 *(TDD §4.1; schema `vms.floors`)* · P1 · 3 pts · 1 story · 3 tasks

#### US-04.2.1 — Manage floors within a building

**As a** System Administrator **I want** to manage floors under a building **so that** receptions and
tenants can be located, and the 120-reception structure of FR-ADM-02 (SRS B1) has a data home.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-CFG-03 *(TDD §4.1; schema `vms.floors`)* |
| **Dependencies** | US-04.1.1 |
| **Blocked by** | TODO-01 — FR-CFG-03 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** an active building, **When** I create a floor with a `code` unique within that
  building, a `name` and an optional `level_no`, **Then** the row is written to `vms.floors` and
  audited.
- **AC-2 — Given** floors exist, **When** I list them, **Then** I can filter by building and active
  status, and results order by `level_no` where present and `code` otherwise.
- **AC-3 — Given** a floor, **When** I deactivate it, **Then** `is_active` becomes false and it is no
  longer offered when creating a reception or assigning a tenant.
- **AC-4 (negative) — Given** a floor code that already exists within the same building, **When** I
  create it, **Then** the request is rejected, honouring the composite unique constraint on
  (`building_id`, `code`); the same code in a different building is accepted.
- **AC-5 (negative) — Given** an inactive or non-existent building, **When** I create a floor under
  it, **Then** the request is rejected with a validation error naming `building_id`.
- **AC-6 (negative) — Given** a floor with receptions, **When** deletion is attempted, **Then** it is
  not offered, because `vms.receptions.floor_id` is `ON DELETE RESTRICT`.

**Development Tasks**

**T-04.2.1.1 — Floor domain model and parent-building validation** · `P1` · `1 pts` · deps: `T-04.1.1.1`
- **Description:** Apply the shared master data pattern to the `Floor` entity within the `Building`
  aggregate boundary, with validation that `building_id` references an active building and that
  `code` is unique within the building.
- **Acceptance Criteria:** Creation under an inactive building is rejected; duplicate code within a
  building is rejected; the same code under a different building is accepted; unit tested.
- **Dependencies:** T-04.1.1.1

**T-04.2.1.2 — Floor persistence adapter and ordered queries** · `P1` · `1 pts` · deps: `T-04.2.1.1`
- **Description:** Implement the repository over `vms.floors` using the `idx_floors_building` access
  path, with composite-constraint conflict translation and ordering by `level_no` then `code`.
- **Acceptance Criteria:** Queries filter by building and active status; ordering is deterministic
  when `level_no` is null; the composite unique violation yields a conflict result.
- **Dependencies:** T-04.2.1.1

**T-04.2.1.3 — Floor API, guarded, flagged and audited** · `P1` · `1 pts` · deps: `T-04.2.1.2`
- **Description:** Expose floor endpoints under the building resource path, guarded by the master
  data permissions, behind the feature flag, with audit events on every mutation.
- **Acceptance Criteria:** Permission enforcement matches F-04.1; deactivating a floor removes it
  from dependent selection lists; every mutation is audited with both states.
- **Dependencies:** T-04.2.1.2, T-03.2.1.2, T-05.1.1.2

---

### F-04.3 — Tenant master data

Tenant organisations in `vms.tenants`, optionally located on a floor.

`TDD-DERIVED` FR-CFG-04 *(TDD §4.1; schema `vms.tenants`)* · P1 · 3 pts · 1 story · 3 tasks

#### US-04.3.1 — Manage tenants

**As a** System Administrator **I want** to manage the tenant register with contact details and floor
location **so that** tenant users, hosts and visitor requests in Phase 2 have a valid tenant to
belong to.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-CFG-04 *(TDD §4.1; schema `vms.tenants`)* |
| **Dependencies** | US-04.2.1 |
| **Blocked by** | TODO-01 — FR-CFG-04 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** I hold `masterdata.edit`, **When** I create a tenant with a unique `code`, a
  `name`, an optional `floor_id` and optional `contact_email` and `contact_phone`, **Then** the row
  is written to `vms.tenants` and audited.
- **AC-2 — Given** `contact_email` is a `citext` column, **When** I create a tenant with an email
  differing only in case from an existing one, **Then** it is accepted, because the schema places no
  unique constraint on tenant contact email — only `code` is unique.
- **AC-3 — Given** a tenant, **When** I deactivate it, **Then** `is_active` becomes false, users
  assigned to that tenant are reported to the administrator before confirmation, and the tenant no
  longer appears in selection lists.
- **AC-4 (negative) — Given** a duplicate tenant `code`, **When** I create or rename to it, **Then**
  the request is rejected with a validation error naming the field.
- **AC-5 (negative) — Given** a malformed `contact_email` or `contact_phone`, **When** I submit it,
  **Then** the request is rejected with a field-level validation error and no partial row is written.
- **AC-6 (negative) — Given** tenant contact details are personal data, **When** any tenant record is
  created, updated or read, **Then** no `contact_email` or `contact_phone` value appears in any
  application log, and the audit event stores them only within the structured before/after payload.

**Development Tasks**

**T-04.3.1.1 — Tenant domain model and contact validation** · `P1` · `1 pts` · deps: `T-04.2.1.1`
- **Description:** Apply the shared master data pattern to the `Tenant` aggregate over
  `vms.tenants`, with email and phone format validation, an optional active-floor reference, and no
  invented attributes beyond the schema columns.
- **Acceptance Criteria:** Malformed contact values are rejected; an inactive floor reference is
  rejected; a null `floor_id` is accepted per the schema; unit tested including failure paths.
- **Dependencies:** T-04.2.1.1

**T-04.3.1.2 — Tenant persistence adapter and dependent-user reporting** · `P1` · `1 pts` · deps: `T-04.3.1.1`
- **Description:** Implement the repository over `vms.tenants` using `idx_tenants_floor`, plus a
  query reporting the count of active `vms.users` rows referencing the tenant, shown before a
  deactivation is confirmed.
- **Acceptance Criteria:** Deactivation surfaces the dependent active-user count; the count is
  accurate under concurrent user creation; queries are paginated.
- **Dependencies:** T-04.3.1.1

**T-04.3.1.3 — Tenant API with PII-safe logging and audit** · `P1` · `1 pts` · deps: `T-04.3.1.2`
- **Description:** Expose tenant endpoints guarded by the master data permissions, behind the feature
  flag, with audit events, and with `contact_email` and `contact_phone` registered as redacted fields
  in the F-01.7 logging configuration.
- **Acceptance Criteria:** A test asserts no tenant contact value reaches any log at any level;
  mutations are audited with both states; permission enforcement matches F-04.1.
- **Dependencies:** T-04.3.1.2, T-01.7.1.1, T-05.1.1.2

---

### F-04.4 — Reception point master data

Reception points per floor in `vms.receptions`, including the central reception flag.

`TDD-DERIVED` FR-CFG-05 *(TDD §4.1; schema `vms.receptions`)* · P1 · 3 pts · 1 story · 3 tasks

#### US-04.4.1 — Manage reception points

**As a** System Administrator **I want** to manage reception points on each floor and designate the
central reception **so that** the 120 floor receptions plus one central reception of FR-ADM-02
(SRS B1) are represented, and reception users can be assigned to a real location.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-CFG-05 *(TDD §4.1; schema `vms.receptions`)*; consumed by FR-ADM-01 (SRS B1) and FR-ADM-02 (SRS B1) |
| **Dependencies** | US-04.2.1 |
| **Blocked by** | TODO-01 — FR-CFG-05 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** an active floor, **When** I create a reception with a `code` unique within that
  floor, a `name` and an `is_central` flag, **Then** the row is written to `vms.receptions` and
  audited.
- **AC-2 — Given** FR-ADM-01 (SRS B1) names a single central reception, **When** I set `is_central`
  true on a reception while another already has it, **Then** the operation requires explicit
  confirmation and records the previous holder in the audit event.
- **AC-3 — Given** receptions exist, **When** I list them, **Then** I can filter by floor, building,
  central flag and active status, and the total count is visible so the administrator can verify the
  120+1 structure.
- **AC-4 (negative) — Given** a duplicate reception `code` within the same floor, **When** I create
  it, **Then** the request is rejected, honouring the (`floor_id`, `code`) unique constraint.
- **AC-5 (negative) — Given** a reception with assigned active users, **When** I deactivate it,
  **Then** the dependent active-user count is shown and confirmation is required, and the users'
  `reception_id` is left intact rather than nulled.
- **AC-6 (negative) — Given** the reception designated central is the one referenced by the sole
  active Master Admin, **When** I attempt to deactivate it, **Then** the operation is refused,
  because it would strand the FR-ADM-01 (SRS B1) authority.

**Development Tasks**

**T-04.4.1.1 — Reception domain model with central-designation rule** · `P1` · `1 pts` · deps: `T-04.2.1.1`
- **Description:** Apply the shared master data pattern to the `Reception` entity over
  `vms.receptions`, with floor-scoped code uniqueness and an explicit-confirmation rule for
  transferring the `is_central` designation, evaluated within one transaction.
- **Acceptance Criteria:** Floor-scoped duplicate code is rejected; transferring `is_central`
  requires confirmation and is atomic; two concurrent transfers do not both succeed silently.
- **Dependencies:** T-04.2.1.1

**T-04.4.1.2 — Reception persistence adapter and dependency queries** · `P1` · `1 pts` · deps: `T-04.4.1.1`
- **Description:** Implement the repository over `vms.receptions` using `idx_receptions_floor`, with
  filtered counting queries by floor, building and central flag, and a dependent active-user count
  used by the deactivation confirmation.
- **Acceptance Criteria:** Filters and counts are correct; the dependent-user count drives the
  confirmation; deactivation leaves `vms.users.reception_id` values unchanged.
- **Dependencies:** T-04.4.1.1

**T-04.4.1.3 — Reception API with Master Admin stranding guard** · `P1` · `1 pts` · deps: `T-04.4.1.2`
- **Description:** Expose reception endpoints guarded by the master data permissions, behind the
  feature flag, audited, and add the guard refusing deactivation of the central reception referenced
  by the last active `MASTER_ADMIN`.
- **Acceptance Criteria:** The stranding guard refuses the offending deactivation with a clear
  reason; the guard and US-02.4.1's last-Master-Admin guard cannot be circumvented by ordering the
  two operations; every mutation is audited.
- **Dependencies:** T-04.4.1.2, T-02.4.1.2, T-05.1.1.2

---

### F-04.5 — Visitor type master data

Visitor classification codes in `vms.visitor_types`.

`TDD-DERIVED` FR-CFG-06 *(TDD §4.1; schema `vms.visitor_types`)* · P2 · 2 pts · 1 story · 2 tasks

#### US-04.5.1 — Manage visitor types

**As a** System Administrator **I want** to manage the visitor type list **so that** Phase 2 visitor
records can be classified consistently.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 2 |
| **Provenance** | `TDD-DERIVED` FR-CFG-06 *(TDD §4.1; schema `vms.visitor_types`)* |
| **Dependencies** | US-04.1.1 |
| **Blocked by** | TODO-01 — FR-CFG-06 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** the baseline seed, **When** I list visitor types, **Then** `GUEST`, `CONTRACTOR`,
  `VIP` and `INTERVIEW` are present and active.
- **AC-2 — Given** I hold `masterdata.edit`, **When** I create a visitor type with a unique `code`, a
  `name` and an optional `description`, **Then** the row is written and audited.
- **AC-3 — Given** a visitor type, **When** I deactivate it, **Then** `is_active` becomes false and
  it is no longer offered for new visitor records, while existing references remain valid.
- **AC-4 (negative) — Given** a duplicate `code`, **When** I create it, **Then** the request is
  rejected with a validation error naming the field.
- **AC-5 (negative) — Given** deletion would break `vms.visitors.visitor_type_id`, **When** I look
  for a delete action, **Then** none is offered — only deactivation — even though the schema uses
  `ON DELETE SET NULL`, because silently nulling a visitor's classification loses information.

**Development Tasks**

**T-04.5.1.1 — Visitor type domain model and persistence** · `P2` · `1 pts` · deps: `T-04.1.1.1`
- **Description:** Apply the shared master data pattern to `VisitorType` over `vms.visitor_types`
  with unique-code conflict translation and paged, filtered queries.
- **Acceptance Criteria:** Seeded types load; duplicate code yields a conflict; no delete operation
  exists; unit and integration tested.
- **Dependencies:** T-04.1.1.1

**T-04.5.1.2 — Visitor type API, guarded, flagged and audited** · `P2` · `1 pts` · deps: `T-04.5.1.1`
- **Description:** Expose the endpoints guarded by the master data permissions, behind the feature
  flag, with audit events on every mutation.
- **Acceptance Criteria:** Read requires `masterdata.view`, mutation `masterdata.edit`; flag-off
  returns 404; every mutation writes one audit row with both states.
- **Dependencies:** T-04.5.1.1, T-03.2.1.2, T-05.1.1.2

---

### F-04.6 — Pass type master data

Pass types in `vms.pass_types`, carrying the credential and restriction defaults Phase 2 consumes.

`TDD-DERIVED` FR-CFG-07 *(TDD §4.1; schema `vms.pass_types`)* · P1 · 3 pts · 1 story · 3 tasks

#### US-04.6.1 — Manage pass types and their credential defaults

**As a** System Administrator **I want** to manage pass types with their default credential type,
restriction type and validity hours **so that** Phase 2 credential requests to ACS have consistent,
configurable defaults rather than hard-coded values.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-CFG-07 *(TDD §4.1; schema `vms.pass_types`)*; consumed by FR-VMS-11 (SRS B1) and FR-VMS-13 (SRS B1) in Phase 2 |
| **Dependencies** | US-04.1.1 |
| **Blocked by** | TODO-01 — FR-CFG-07 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** the baseline seed, **When** I list pass types, **Then** `DAY_QR`, `ONE_TIME` and
  `CARD_DAY` exist with the seeded defaults.
- **AC-2 — Given** I hold `masterdata.edit`, **When** I create a pass type with a unique `code`, a
  `name`, a `default_credential` of `qr` or `rfid`, a `default_restriction` of `time_bound` or
  `one_time`, and a positive `default_valid_hours`, **Then** the row is written and audited.
- **AC-3 — Given** the enum-constrained columns, **When** I submit a value outside
  `vms.credential_type` or `vms.restriction_type`, **Then** the request is rejected at validation with
  the permitted values listed, before reaching the database.
- **AC-4 — Given** a pass type is changed, **When** the change is saved, **Then** the audit event
  records both states, because these defaults determine credential validity windows requested from
  ACS and must be reconstructible after the fact.
- **AC-5 (negative) — Given** `default_valid_hours` of zero or a negative number, **When** I submit
  it, **Then** the request is rejected, matching the schema `CHECK (default_valid_hours > 0)` rather
  than relying on the database to reject it.
- **AC-6 (negative) — Given** a pass type referenced by existing credentials, **When** I deactivate
  it, **Then** deactivation succeeds and existing references remain intact, but the type is no longer
  offered for new credential requests.

**Development Tasks**

**T-04.6.1.1 — Pass type domain model with enum and range validation** · `P1` · `1 pts` · deps: `T-04.1.1.1`
- **Description:** Apply the shared master data pattern to `PassType` over `vms.pass_types`, mapping
  `vms.credential_type` and `vms.restriction_type` to domain enums and validating
  `default_valid_hours` as strictly positive in the domain.
- **Acceptance Criteria:** Invalid enum values are rejected with the permitted set listed;
  non-positive hours are rejected in the domain, not only by the database constraint; unit tested.
- **Dependencies:** T-04.1.1.1

**T-04.6.1.2 — Pass type persistence adapter with PostgreSQL enum mapping** · `P1` · `1 pts` · deps: `T-04.6.1.1`
- **Description:** Implement the repository over `vms.pass_types` with correct bidirectional mapping
  of the PostgreSQL enum types, and paged filtered queries.
- **Acceptance Criteria:** Every seeded row round-trips without value loss; an unmapped enum value
  fails loudly at read rather than defaulting; integration tested against Testcontainers.
- **Dependencies:** T-04.6.1.1

**T-04.6.1.3 — Pass type API, guarded, flagged and audited** · `P1` · `1 pts` · deps: `T-04.6.1.2`
- **Description:** Expose the endpoints guarded by the master data permissions, behind the feature
  flag, with mandatory before/after audit payloads given these values drive ACS credential requests.
- **Acceptance Criteria:** Permission enforcement matches F-04.1; every mutation writes an audit row
  containing both complete states; flag-off returns 404.
- **Dependencies:** T-04.6.1.2, T-03.2.1.2, T-05.1.1.2

---

### F-04.7 — Holiday calendar

Non-working and working-exception dates in `vms.holiday_calendar`.

`TDD-DERIVED` FR-CFG-08 *(TDD §4.1; schema `vms.holiday_calendar`)* · P2 · 3 pts · 1 story · 3 tasks

#### US-04.7.1 — Maintain the holiday calendar

**As a** System Administrator **I want** to maintain a calendar of holidays and working exceptions
**so that** later scheduling and reporting logic has an authoritative definition of a non-working
day.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-CFG-08 *(TDD §4.1; schema `vms.holiday_calendar`)* |
| **Dependencies** | US-04.1.1 |
| **Blocked by** | TODO-01 — FR-CFG-08 has no SRS B1 definition. Backlog only. No behaviour is attached to a holiday in this phase; the calendar is data only, because no SRS requirement states what a holiday should change. |

**Acceptance Criteria**
- **AC-1 — Given** I hold `masterdata.edit`, **When** I add an entry with a `holiday_date`, a `name`
  and an `is_working` flag, **Then** the row is written to `vms.holiday_calendar` and audited.
- **AC-2 — Given** entries exist, **When** I view the calendar, **Then** I can list by year and by
  date range, and each entry shows whether it is a non-working holiday or a working exception.
- **AC-3 — Given** a bulk year import, **When** I submit a set of dates, **Then** the whole set is
  validated first and applied in one transaction, with a per-row outcome report.
- **AC-4 — Given** an entry, **When** I remove it, **Then** the row is deleted (this table carries no
  `is_active` column and no foreign-key dependants) and the deletion is audited with the prior state
  so it is reconstructible.
- **AC-5 (negative) — Given** a `holiday_date` that already exists, **When** I add it again, **Then**
  the request is rejected, honouring the unique constraint on `holiday_date`.
- **AC-6 (negative) — Given** a bulk import containing one duplicate date, **When** it is applied,
  **Then** no row from that import is written until the conflict is resolved, so the calendar is
  never left half-imported.

**Development Tasks**

**T-04.7.1.1 — Holiday calendar domain model and date validation** · `P2` · `1 pts` · deps: `T-04.1.1.1`
- **Description:** Model the holiday calendar entry over `vms.holiday_calendar` with date uniqueness,
  an explicit `is_working` exception flag, and a documented decision that no scheduling behaviour is
  attached in this phase.
- **Acceptance Criteria:** Duplicate dates are rejected; `is_working` defaults to false per the
  schema; no other component consumes the calendar in this phase; unit tested.
- **Dependencies:** T-04.1.1.1

**T-04.7.1.2 — Bulk year import with all-or-nothing semantics** · `P2` · `1 pts` · deps: `T-04.7.1.1`
- **Description:** Implement validate-then-apply bulk import in a single transaction with a per-row
  outcome report and no partial application on any conflict.
- **Acceptance Criteria:** A set containing one duplicate writes nothing and reports the offending
  row; a clean set applies fully; the import is audited as one summary event plus per-row entries.
- **Dependencies:** T-04.7.1.1

**T-04.7.1.3 — Holiday calendar API, guarded, flagged and audited** · `P2` · `1 pts` · deps: `T-04.7.1.2`
- **Description:** Expose the calendar endpoints guarded by the master data permissions, behind the
  feature flag, with year and range queries and audit events including prior state on delete.
- **Acceptance Criteria:** Permission enforcement matches F-04.1; a delete's audit row contains the
  full prior state; year and range queries return the correct sets.
- **Dependencies:** T-04.7.1.2, T-03.2.1.2, T-05.1.1.2

---

### F-04.8 — System settings

Typed, audited application settings in `vms.system_settings`.

`TDD-DERIVED` FR-SET-01 *(schema §11 `vms.system_settings` — no SRS B1 definition)* · P1 · 5 pts · 2 stories · 5 tasks

#### US-04.8.1 — Read and change system settings

**As a** System Administrator **I want** to view and change application-wide settings **so that**
operational behaviour can be adjusted without a code release, with a record of who changed what.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-SET-01 *(schema `vms.system_settings`)* |
| **Dependencies** | US-01.5.1, US-03.2.1, US-05.1.1 |
| **Blocked by** | TODO-01 — FR-SET-01 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** the baseline seed, **When** I list settings, **Then** `default_pass_valid_hours`,
  `notification.email.enabled`, `notification.whatsapp.enabled` and `acs.retry.max_attempts` are
  present with their seeded values and descriptions.
- **AC-2 — Given** I hold `settings.manage`, **When** I change a setting, **Then** the `value` jsonb
  is updated, `updated_by` is set to my user id and `updated_at` to now, and an audit event records
  the key with both states.
- **AC-3 — Given** `notification.whatsapp.enabled` is seeded false, **When** I attempt to enable it,
  **Then** the change is refused with a message citing TODO-05 (WhatsApp Business API approval
  outstanding), because enabling it would activate an unapproved channel.
- **AC-4 (negative) — Given** I hold `masterdata.edit` but not `settings.manage`, **When** I attempt
  a setting change, **Then** the request is denied with 403 and audited — settings are deliberately a
  separate permission from master data.
- **AC-5 (negative) — Given** a setting key that does not exist, **When** I attempt to write it,
  **Then** the request is rejected: keys are a fixed, reviewed catalogue, not free-form, so a typo
  cannot create a silently ignored setting.

**Development Tasks**

**T-04.8.1.1 — Settings catalogue and repository** · `P1` · `1 pts` · deps: `T-01.5.1.3`
- **Description:** Define a reviewed catalogue of permitted setting keys with their types, defaults
  and descriptions, verified against `vms.system_settings` by a test, and implement the repository
  with `updated_by` and `updated_at` maintenance.
- **Acceptance Criteria:** A test fails if a seeded key is absent from the catalogue or vice versa;
  writing an unknown key is rejected; `updated_by` is always populated on change.
- **Dependencies:** T-01.5.1.3

**T-04.8.1.2 — Settings API guarded by `settings.manage` with audit** · `P1` · `1 pts` · deps: `T-04.8.1.1`
- **Description:** Expose read and write endpoints, read guarded by `masterdata.view` and write
  strictly by `settings.manage`, with audit events carrying key and both states.
- **Acceptance Criteria:** A principal with `masterdata.edit` but not `settings.manage` is denied on
  write; every change writes one audit row; the endpoints are behind the master data feature flag.
- **Dependencies:** T-04.8.1.1, T-03.2.1.2, T-05.1.1.2

**T-04.8.1.3 — Guard on the WhatsApp enablement setting** · `P1` · `1 pts` · deps: `T-04.8.1.2`
- **Description:** Add a rule refusing any change setting `notification.whatsapp.enabled` to true
  while TODO-05 is open, with the refusal message naming the open question.
- **Acceptance Criteria:** Enabling the flag is refused with the TODO-05 reason; disabling and
  re-reading behave normally; the refusal is audited as an attempted change.
- **Dependencies:** T-04.8.1.2

#### US-04.8.2 — Typed setting access with secret redaction

**As a** System Administrator **I want** application code to read settings through a typed accessor
that refuses to expose secret-valued settings **so that** a configuration value cannot become the way
a secret leaks into a response or a log.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 2 |
| **Provenance** | `TDD-DERIVED` FR-SET-01 *(schema `vms.system_settings`)*; `ENABLER` justified against NFR-SEC-01 (SRS B1) |
| **Dependencies** | US-04.8.1, US-05.2.1 |
| **Blocked by** | TODO-01 — FR-SET-01 has no SRS B1 definition. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** the typed accessor, **When** code reads a setting, **Then** it receives a
  correctly typed value or a documented default, never a raw jsonb node, and a type mismatch fails
  loudly at read.
- **AC-2 — Given** the catalogue marks a key as secret-valued, **When** it is read through the API or
  logged, **Then** the value is replaced with a fixed redaction marker and only the fact of its
  presence is disclosed.
- **AC-3 — Given** settings are read frequently, **When** they are accessed, **Then** values are
  served from an in-memory or Redis cache invalidated on change, so a read is not a database round
  trip per request.
- **AC-4 (negative) — Given** an attempt to store an actual secret (an API key or password) in
  `vms.system_settings`, **When** it is written, **Then** it is refused: secrets belong in the F-05.2
  secrets mechanism, and the settings table is not an approved secret store.

**Development Tasks**

**T-04.8.2.1 — Typed settings accessor with cached reads** · `P1` · `1 pts` · deps: `T-04.8.1.1`
- **Description:** Implement a typed accessor over the settings catalogue with per-key type coercion,
  documented defaults, and a cache invalidated on write.
- **Acceptance Criteria:** Each catalogue key resolves to its declared type; a mismatched stored value
  fails loudly; a change is visible to readers without a restart.
- **Dependencies:** T-04.8.1.1

**T-04.8.2.2 — Secret-valued key redaction and write refusal** · `P1` · `1 pts` · deps: `T-04.8.2.1`
- **Description:** Add a secret-valued marker to the catalogue with redaction on read and in logs,
  and a heuristic refusal for writes whose key or value shape indicates a credential, directing the
  operator to the F-05.2 mechanism.
- **Acceptance Criteria:** A key marked secret-valued never returns its value through the API; a
  write resembling a credential is refused with a message naming the secrets mechanism; a test
  asserts no setting value appears in logs for keys marked secret-valued.
- **Dependencies:** T-04.8.2.1, T-05.2.1.2

---

### F-04.9 — Master data caching & invalidation

Redis read-through caching for reference data, invalidated by domain events over Kafka.

`TDD-DERIVED` TDD §4.1 · P1 · 8 pts · 2 stories · 6 tasks

#### US-04.9.1 — Read-through cache for reference data

**As a** Floor Receptionist **I want** reference data lookups to be served from cache **so that** the
portal stays responsive with 120 concurrent reception users, per NFR-SCL-01 (SRS B1).

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §4.1; justified against NFR-SCL-01 (SRS B1) |
| **Dependencies** | US-04.1.1, US-04.6.1, US-01.3.1 |
| **Blocked by** | TODO-01 — the cached entities are themselves TDD-derived. Backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** a reference data lookup, **When** it is requested and absent from cache, **Then**
  it is loaded from PostgreSQL, stored in Redis with a bounded TTL, and returned; a subsequent
  request within the TTL is served without a database round trip.
- **AC-2 — Given** cache keys, **When** I inspect them, **Then** they are namespaced by entity type
  and version, contain no personal data, and change wholesale when the cache format changes so a
  deployment cannot read a stale shape.
- **AC-3 — Given** Redis is unavailable, **When** a lookup is made, **Then** it falls through to
  PostgreSQL and succeeds, with a cache-degraded metric incremented — the cache is an optimisation,
  never a dependency.
- **AC-4 — Given** cache metrics, **When** they are scraped, **Then** hit rate, miss rate and load
  latency are exposed per entity type.
- **AC-5 (negative) — Given** a cached entity that has been deactivated, **When** the cache is stale
  within its TTL, **Then** any write path that depends on the entity being active re-validates
  against PostgreSQL rather than trusting the cache, so a deactivated tenant cannot be used as a
  valid reference.
- **AC-6 (negative) — Given** a cache serialisation failure, **When** it occurs, **Then** the request
  succeeds from the database, the failure is logged without the payload contents, and the poisoned
  key is evicted.

**Development Tasks**

**T-04.9.1.1 — Redis cache abstraction with versioned namespacing** · `P1` · `2 pts` · deps: `T-01.3.1.1`
- **Description:** Implement a cache port in `application` and a Redis adapter in `infrastructure`
  providing read-through semantics, versioned per-entity key namespaces, bounded TTLs, and
  serialisation that excludes personal data fields.
- **Acceptance Criteria:** Keys carry the entity type and format version; a format version bump
  invalidates the whole namespace; no cached payload contains a contact email or phone.
- **Dependencies:** T-01.3.1.1, T-01.2.1.3

**T-04.9.1.2 — Cache-fallthrough and poison-key resilience** · `P1` · `2 pts` · deps: `T-04.9.1.1`
- **Description:** Implement degradation behaviour — Redis unavailability or a deserialisation
  failure falls through to PostgreSQL, evicts the offending key, and increments a degraded metric —
  and add the write-path re-validation rule that active-status checks always hit the database.
- **Acceptance Criteria:** With Redis stopped, all reference reads still succeed; a corrupted value
  is evicted and the read succeeds; a deactivated tenant is rejected on a write path even while a
  stale cache entry exists.
- **Dependencies:** T-04.9.1.1

**T-04.9.1.3 — Cache metrics per entity type** · `P2` · `1 pts` · deps: `T-04.9.1.2`
- **Description:** Expose hit, miss, load-latency and degraded-mode metrics dimensioned by entity
  type through the F-01.7 metrics pipeline.
- **Acceptance Criteria:** Metrics appear per entity type on the management endpoint; labels contain
  no identifiers; degraded mode is distinguishable from a normal miss.
- **Dependencies:** T-04.9.1.2, T-01.7.1.3

#### US-04.9.2 — Event-driven cache invalidation

**As a** System Administrator **I want** a master data change to invalidate its cache entries across
every application instance immediately **so that** an administrator's correction takes effect
everywhere without waiting for a TTL.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` TDD §4.1; justified against NFR-SCL-01 (SRS B1) |
| **Dependencies** | US-04.9.1, US-01.3.1 |
| **Blocked by** | TODO-01 — backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** any master data mutation, **When** the transaction commits, **Then** a
  `MasterDataChanged` domain event carrying the entity type and id is published to Kafka — published
  after commit, never inside the transaction.
- **AC-2 — Given** multiple application instances, **When** the event is consumed, **Then** every
  instance evicts the affected cache entries, and a subsequent read on any instance reflects the
  change.
- **AC-3 — Given** an event is redelivered, **When** it is consumed again, **Then** the eviction is
  idempotent and produces no error.
- **AC-4 (negative) — Given** the mutation transaction rolls back after the event would have been
  published, **When** the outcome is observed, **Then** no event is published, so the cache is never
  invalidated for a change that did not happen.
- **AC-5 (negative) — Given** Kafka is unavailable at publication time, **When** the mutation
  commits, **Then** the mutation still succeeds, the event is retained for later dispatch, and cache
  correctness degrades only to the TTL bound rather than being lost.

**Development Tasks**

**T-04.9.2.1 — Post-commit domain event publication** · `P1` · `1 pts` · deps: `T-04.1.1.3`
- **Description:** Implement transactional event publication for master data mutations — events
  recorded within the transaction and dispatched to Kafka only after commit — so a rollback publishes
  nothing.
- **Acceptance Criteria:** A committed mutation publishes exactly one event; a rolled-back mutation
  publishes none; the event payload carries entity type and id only, with no personal data.
- **Dependencies:** T-04.1.1.3, T-01.3.1.1

**T-04.9.2.2 — Idempotent invalidation consumer** · `P1` · `1 pts` · deps: `T-04.9.2.1`
- **Description:** Implement the Kafka consumer evicting the affected cache namespace entries on
  every instance, idempotent under redelivery, propagating the correlation id from message headers.
- **Acceptance Criteria:** All instances reflect the change after one event; a redelivered event is a
  no-op; consumer logs carry the originating correlation id.
- **Dependencies:** T-04.9.2.1, T-01.7.1.2

**T-04.9.2.3 — Kafka-unavailable retention and TTL fallback** · `P2` · `1 pts` · deps: `T-04.9.2.2`
- **Description:** Retain undispatched events durably when Kafka is unavailable, dispatch on
  recovery, and document that cache staleness is bounded by the TTL in the interim.
- **Acceptance Criteria:** With Kafka stopped, mutations still succeed and events are retained;
  events dispatch on recovery in order per entity; the TTL bound is documented and tested.
- **Dependencies:** T-04.9.2.2

---

## EPIC-05 — Audit, Security & Compliance Foundation

| | |
|---|---|
| **Provenance** | `TDD-DERIVED` / `SRS` / `SRS-NFR` / `BLOCKED` / `ENABLER` |
| **Requirement IDs** | FR-API-03 (SRS B1), NFR-SEC-01 (SRS B1), NFR-CMP-01 (SRS B1); FR-AUD-01 *(TDD/schema reference — undefined in SRS B1)* |
| **Priority** | P0 |
| **Features** | 5 · **Stories** 6 · **Tasks** 19 · **Points** 26 |
| **Open questions** | TODO-01 (F-05.1 rests on FR-AUD-01), TODO-06 (hosting region / data residency), TODO-12 (retention period), TODO-13 (`id_document_ref` scope) |

**Goal.** Make the platform defensible. An append-only audit trail over `vms.audit_logs` that every
other epic in this phase already writes into; a secrets mechanism so no credential — the ACS API
credential FR-API-03 (SRS B1) requires, the JWT signing key, the database password — ever appears in
source, an image layer, a settings row or a log; encryption in transit and at rest for the visitor
personal data NFR-CMP-01 (SRS B1) is concerned with; a retention and purge seam that is deliberately
inert until TODO-12 supplies a period; and an OWASP ASVS baseline verified in CI rather than
asserted in a document. This epic is where the security posture of the whole system is either
established or quietly not.

> **Note on FR-AUD-01.** The audit log is referenced by TDD §4.6/§7 and schema §11 but has no SRS B1
> definition, so F-05.1 is `TDD-DERIVED` and carries TODO-01. It is nonetheless treated as P0
> because NFR-SEC-01 (SRS B1) and FR-API-02 (SRS B1) both depend on an attributable trail existing,
> and because every other Phase 1 epic writes to it. If TODO-01 removes it, a great deal of already
> written behaviour would have to be unpicked — this should be raised with the client as a
> disposition priority.

---

### F-05.1 — Append-only audit log

The trail over `vms.audit_logs`: who did what, to what, with before and after state.

`TDD-DERIVED` FR-AUD-01 *(TDD §4.6, §7; schema `vms.audit_logs`)* · P0 · 8 pts · 2 stories · 7 tasks

#### US-05.1.1 — Write append-only audit events on every state change

**As a** System Administrator **I want** every state change and security-relevant event recorded in
an append-only audit log **so that** an action can be attributed to a person after the fact and the
record cannot be quietly altered.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` FR-AUD-01 *(schema `vms.audit_logs`)*; justified against NFR-SEC-01 (SRS B1) and FR-API-02 (SRS B1) |
| **Dependencies** | US-01.5.1, US-01.7.1 |
| **Blocked by** | TODO-01 — FR-AUD-01 has no SRS B1 definition. Built as P0 on the NFR-SEC-01 (SRS B1) justification; flagged for TODO-01 disposition. |

**Acceptance Criteria**
- **AC-1 — Given** any create, update, deactivate or security-relevant event, **When** it commits,
  **Then** a row is written to `vms.audit_logs` with `user_id`, `action` in dotted form (for example
  `masterdata.update`, `user.deactivate`, `auth.login.failure`), `entity_type`, `entity_id`,
  `before_state`, `after_state` and `ip_address`.
- **AC-2 — Given** the audit table, **When** any role other than the migration owner attempts an
  `UPDATE` or `DELETE` on `vms.audit_logs`, **Then** the statement is refused at the database level
  by a trigger or revoked grant — append-only is enforced by PostgreSQL, not by application
  convention.
- **AC-3 — Given** an audited operation, **When** its business transaction rolls back, **Then** no
  audit row remains for it — the audit write participates in the same transaction, so the trail never
  claims something that did not happen.
- **AC-4 — Given** an unauthenticated event such as a failed login, **When** it is audited, **Then**
  `user_id` is null, the attempted identifier is recorded in the payload, and the row is still
  written.
- **AC-5 (negative) — Given** an audited entity containing personal data (a user's email, a tenant
  contact phone), **When** the audit row is written, **Then** the values appear only inside the
  structured `before_state`/`after_state` jsonb and never in the `action` or `entity_id` fields, and
  no password hash, token, activation token or QR payload appears in any audit field.
- **AC-6 (negative) — Given** the audit writer itself fails, **When** an audited operation runs,
  **Then** the business operation fails too rather than succeeding unaudited, for every operation
  classified as security-relevant.

**Development Tasks**

**T-05.1.1.1 — Append-only enforcement at the database level** · `P0` · `1 pts` · deps: `T-01.5.1.2`
- **Description:** Add a migration installing a trigger on `vms.audit_logs` that raises on `UPDATE`
  and `DELETE`, and revoke those grants from the application role, leaving `INSERT` and `SELECT`.
- **Acceptance Criteria:** An integration test attempting an update and a delete as the application
  role fails in both cases; inserts and selects succeed; the migration is idempotent.
- **Dependencies:** T-01.5.1.2

**T-05.1.1.2 — Transactional audit writer with sensitive-field exclusion** · `P0` · `2 pts` · deps: `T-05.1.1.1`
- **Description:** Implement the audit writer port in `application` and its adapter over
  `vms.audit_logs`, participating in the caller's transaction, capturing actor from the scope
  context, source IP from the request, and serialising before/after state through a filter that drops
  `password_hash`, tokens, activation tokens and `qr_payload` entirely.
- **Acceptance Criteria:** A rolled-back operation leaves no audit row; excluded fields never appear
  in a serialised state; an unauthenticated event writes a row with null `user_id`; unit and
  integration tested.
- **Dependencies:** T-05.1.1.1, T-03.4.1.1

**T-05.1.1.3 — Audit action taxonomy and coverage test** · `P0` · `1 pts` · deps: `T-05.1.1.2`
- **Description:** Define the dotted action code taxonomy as a curated constant set, and add a test
  asserting that every mutating use case registered as security-relevant emits an audit event.
- **Acceptance Criteria:** Every action code used in code exists in the taxonomy; a mutating
  security-relevant use case with no audit emission fails the test naming the use case.
- **Dependencies:** T-05.1.1.2

**T-05.1.1.4 — Fail-closed behaviour on audit write failure** · `P0` · `1 pts` · deps: `T-05.1.1.3`
- **Description:** Ensure an audit write failure on a security-relevant operation propagates and
  fails the business operation, with a documented, reviewed list of non-security-relevant operations
  permitted to proceed on audit failure.
- **Acceptance Criteria:** A simulated audit failure rolls back the business operation for every
  security-relevant case; the permitted exception list is a single reviewed declaration; the failure
  is logged with no payload contents.
- **Dependencies:** T-05.1.1.3

#### US-05.1.2 — Query the audit trail

**As a** System Administrator **I want** to search the audit trail by actor, entity, action and time
range **so that** I can answer who changed a record and when without direct database access.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` FR-AUD-01 *(schema `vms.audit_logs`)*; the full audit report is Phase 4 F-18.4 |
| **Dependencies** | US-05.1.1, US-03.2.1 |
| **Blocked by** | TODO-01 — backlog only. |

**Acceptance Criteria**
- **AC-1 — Given** I hold `report.view`, **When** I query the audit trail filtered by actor, entity
  type, entity id, action and time range, **Then** results are returned paginated and ordered by
  `created_at` descending, using the `idx_audit_user_time` and `idx_audit_entity` access paths.
- **AC-2 — Given** a result row, **When** it is rendered, **Then** the actor is shown as a display
  name resolved from `vms.users`, and a deleted or deactivated actor still resolves rather than
  showing a bare uuid.
- **AC-3 — Given** an audit query, **When** it runs, **Then** the query itself is audited, so
  inspection of the trail is part of the trail.
- **AC-4 (negative) — Given** I do not hold `report.view`, **When** I call the audit query endpoint,
  **Then** the request is denied with 403 and audited.
- **AC-5 (negative) — Given** an unbounded query with no time range on a large table, **When** it is
  submitted, **Then** a maximum range is enforced and an over-wide request is rejected with guidance,
  so the endpoint cannot be used to exfiltrate the whole trail in one call.

**Development Tasks**

**T-05.1.2.1 — Audit query use case with mandatory bounds** · `P1` · `1 pts` · deps: `T-05.1.1.2`
- **Description:** Implement the read use case with keyset pagination, a mandatory bounded time
  range with a configured maximum span, and filters on actor, entity type, entity id and action.
- **Acceptance Criteria:** Filters combine correctly; an over-wide range is rejected; pagination is
  stable under concurrent inserts; queries use the existing indexes.
- **Dependencies:** T-05.1.1.2

**T-05.1.2.2 — Actor resolution for inactive and removed users** · `P1` · `1 pts` · deps: `T-05.1.2.1`
- **Description:** Resolve `user_id` to a display name including for deactivated users, with a
  documented fallback label when the referencing row has been set null by
  `vms.audit_logs.user_id ON DELETE SET NULL`.
- **Acceptance Criteria:** A deactivated actor resolves to their name; a null actor renders the
  documented fallback rather than an empty cell; resolution is batched, not per row.
- **Dependencies:** T-05.1.2.1

**T-05.1.2.3 — Audit query API, guarded and self-auditing** · `P1` · `1 pts` · deps: `T-05.1.2.2`
- **Description:** Expose the query endpoint guarded by `report.view`, emitting an audit event
  recording the query parameters (not the results) on each call.
- **Acceptance Criteria:** `report.view` is enforced with 403 otherwise; each query writes one audit
  row containing the filter parameters and no result content.
- **Dependencies:** T-05.1.2.2, T-03.2.1.2

---

### F-05.2 — Secrets management

No secret in source, image, settings table, or log — ever.

`SRS` FR-API-03 (SRS B1) · TDD §6.3 · P0 · 5 pts · 1 story · 3 tasks

#### US-05.2.1 — Manage secrets outside source and configuration

**As a** System Administrator **I want** every secret resolved at runtime from a dedicated secrets
mechanism **so that** the ACS API credential FR-API-03 (SRS B1) mandates, the JWT signing key and the
database password cannot leak through the repository, an image layer or a log line.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-API-03 (SRS B1) — "authenticate to the ACS API using a credential mechanism … rather than an open endpoint"; NFR-SEC-01 (SRS B1); TDD §6.3 |
| **Dependencies** | US-01.6.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the application starts, **When** it needs a secret, **Then** it resolves it
  through a secrets provider port from a mounted secret or environment-injected value, never from a
  committed file, and fails fast at startup if a required secret is absent.
- **AC-2 — Given** the ACS service credential required by FR-API-03 (SRS B1), **When** it is
  configured, **Then** it is stored only in the secrets mechanism and is retrievable by the F-11.1
  ACS port's adapter alone, not by any other module.
- **AC-3 — Given** a secret is rotated, **When** the new value is published, **Then** the application
  picks it up without a rebuild, and the previous value stops being accepted within the documented
  propagation window.
- **AC-4 — Given** the repository, **When** the secret-scanning job runs over the working tree and
  full history, **Then** it finds no credential, key or token, and the job is a required check.
- **AC-5 (negative) — Given** a secret value, **When** it flows through any log, exception message,
  health endpoint, actuator environment endpoint or API response, **Then** it is absent or replaced
  with a fixed redaction marker — verified by a test that seeds a known sentinel secret and asserts
  it appears nowhere in captured output.
- **AC-6 (negative) — Given** an attempt to store a secret in `vms.system_settings`, **When** it is
  made, **Then** it is refused by the F-04.8 guard, because the settings table is not an approved
  secret store.

**Development Tasks**

**T-05.2.1.1 — Secrets provider port, adapter and fail-fast resolution** · `P0` · `2 pts` · deps: `T-01.6.1.2`
- **Description:** Define the secrets provider port in `application` and implement the adapter
  reading from mounted secret files or injected environment values, with a declared inventory of
  required secrets (datasource password, JWT signing key, ACS service credential) validated at
  startup. The port exists so a managed secret store can replace the adapter once TODO-06 fixes the
  deployment target.
- **Acceptance Criteria:** A missing required secret fails startup naming the secret by identifier,
  not value; the inventory is a single reviewed declaration; no secret is read from a packaged file.
- **Dependencies:** T-01.6.1.2

**T-05.2.1.2 — Secret hygiene: redaction, endpoint lockdown and scanning** · `P0` · `2 pts` · deps: `T-05.2.1.1`
- **Description:** Register secret identifiers with the F-01.7 redaction layer, disable or lock down
  the actuator environment and configuration-properties endpoints in staging and production, and wire
  full-history secret scanning as a required CI check with a documented remediation procedure for a
  historical hit.
- **Acceptance Criteria:** A sentinel secret seeded into configuration appears in no log, no actuator
  response and no error body; environment endpoints are unreachable in non-local profiles; the
  scanning job is a required check and fails on a seeded credential.
- **Dependencies:** T-05.2.1.1, T-01.7.1.1, T-01.4.1.3

**T-05.2.1.3 — Secret rotation procedure and JWT key versioning** · `P1` · `1 pts` · deps: `T-05.2.1.2`
- **Description:** Implement JWT signing key rotation using the key id header so tokens signed by the
  previous key remain verifiable for their remaining lifetime, and document the rotation runbook for
  each inventory secret with its propagation window.
- **Acceptance Criteria:** After rotation, existing valid tokens verify until expiry and new tokens
  use the new key; the previous key is rejected after the documented window; the runbook is committed
  and names every inventory secret.
- **Dependencies:** T-05.2.1.2, T-02.1.1.3

---

### F-05.3 — Data protection — encryption in transit and at rest

TLS everywhere, encrypted storage, and a documented data-protection position.

`SRS-NFR` NFR-CMP-01 (SRS B1) · P0 · 5 pts · 1 story · 3 tasks

#### US-05.3.1 — Encrypt visitor and user data in transit and at rest

**As a** System Administrator **I want** all traffic encrypted in transit and all persisted data
encrypted at rest **so that** visitor personal data is protected as NFR-CMP-01 (SRS B1) requires.

| | |
|---|---|
| **Priority** | P0 |
| **Story Points** | 5 |
| **Provenance** | `SRS-NFR` NFR-CMP-01 (SRS B1); NFR-SEC-01 (SRS B1); OWASP ASVS V9 |
| **Dependencies** | US-01.6.1, US-05.2.1 |
| **Blocked by** | — *(TODO-06 governs hosting region and the applicable data-protection regime; this story delivers the controls, not the residency decision.)* |

**Acceptance Criteria**
- **AC-1 — Given** any client connection, **When** it is established, **Then** it is TLS 1.2 or
  above with weak ciphers disabled, HTTP is redirected to HTTPS, and HSTS is set on the portal
  origin.
- **AC-2 — Given** connections between the application and PostgreSQL, Redis and Kafka, **When** they
  are established in staging and production, **Then** they use TLS with certificate verification
  enabled, not a permissive trust-all mode.
- **AC-3 — Given** the database and its backups, **When** their configuration is reviewed, **Then**
  storage-level encryption at rest is enabled and the position is documented with the key custody
  arrangement.
- **AC-4 — Given** the schema stores personal data in `vms.visitors`, `vms.hosts`, `vms.users` and
  `vms.tenants`, **When** the data protection register is reviewed, **Then** every column holding
  personal data is inventoried with its purpose, and `visitors.id_document_ref` is explicitly flagged
  as open under TODO-13.
- **AC-5 (negative) — Given** a client attempting a plaintext HTTP connection or a TLS 1.0/1.1
  handshake, **When** it connects, **Then** the connection is refused or redirected, and a test
  asserts the refusal for each deprecated protocol version.
- **AC-6 (negative) — Given** a database connection configured without TLS in a non-local profile,
  **When** the application starts, **Then** startup fails rather than silently connecting in
  plaintext.

**Development Tasks**

**T-05.3.1.1 — TLS termination, redirect and HSTS at ingress** · `P0` · `2 pts` · deps: `T-01.6.1.3`
- **Description:** Configure ingress TLS with a minimum protocol version and a reviewed cipher suite,
  HTTP-to-HTTPS redirect, HSTS on the portal origin, and secure cookie attributes where cookies are
  used for the portal session.
- **Acceptance Criteria:** A TLS scan reports no protocol below 1.2 and no weak cipher; HTTP
  redirects; HSTS is present; cookies carry Secure, HttpOnly and an appropriate SameSite value.
- **Dependencies:** T-01.6.1.3

**T-05.3.1.2 — Verified TLS on datastore and broker connections** · `P0` · `2 pts` · deps: `T-05.3.1.1`
- **Description:** Enable TLS with certificate verification on PostgreSQL, Redis and Kafka client
  connections for non-local profiles, sourcing trust material through the secrets mechanism, and fail
  startup if a non-local profile is configured without it.
- **Acceptance Criteria:** Non-local startup without TLS fails with a clear message; certificate
  verification is on, with no trust-all setting in any profile; local profile remains usable without
  certificates.
- **Dependencies:** T-05.3.1.1, T-05.2.1.1

**T-05.3.1.3 — Personal data inventory and at-rest encryption record** · `P1` · `1 pts` · deps: `T-05.3.1.2`
- **Description:** Produce the committed data protection register enumerating every personal-data
  column in the schema with purpose and sensitivity, record the at-rest encryption and key custody
  position, and flag `visitors.id_document_ref` against TODO-13 and residency against TODO-06.
- **Acceptance Criteria:** The register covers every personal-data column in
  `docs/vms_schema_postgresql.sql`; open questions are named with their TODO ids; a test fails if a
  new personal-data column is added without a register entry.
- **Dependencies:** T-05.3.1.2

---

### F-05.4 — Data retention & purge

The retention seam — inert until TODO-12 supplies a period.

`BLOCKED` TODO-12 · P2 · 3 pts · 1 story · 3 tasks

#### US-05.4.1 — Retention and purge framework

**As a** System Administrator **I want** a retention and purge framework in place with no active
policy **so that** a retention period can be applied as configuration the moment it is agreed,
without a new design.

| | |
|---|---|
| **Priority** | P2 |
| **Story Points** | 3 |
| **Provenance** | `BLOCKED` TODO-12; justified against NFR-CMP-01 (SRS B1) |
| **Dependencies** | US-05.1.1, US-05.3.1 |
| **Blocked by** | TODO-12 — no source document states a retention period or purge rule for visitor personal data, yet the schema stores names, emails, phones and `id_document_ref`. **This story builds the mechanism with no policy configured and purge disabled.** No period may be chosen by the delivery team. TODO-13 additionally leaves `id_document_ref` scope open. |

**Acceptance Criteria**
- **AC-1 — Given** the retention framework, **When** it is inspected, **Then** it defines a purge
  job, a per-entity retention policy interface, and an anonymisation strategy interface — with **no
  policy registered and the job disabled by configuration default**.
- **AC-2 — Given** the framework, **When** a policy is registered in a test, **Then** the job selects
  matching rows in bounded batches, applies the strategy, and writes an audit event per batch
  recording counts and the policy identity — never the purged personal data.
- **AC-3 — Given** `vms.audit_logs` is append-only, **When** a purge runs in the test, **Then** it
  does not modify or delete audit rows; the trail of a purge survives the purge.
- **AC-4 — Given** the documentation, **When** it is reviewed, **Then** it states plainly that no
  retention period is agreed, names TODO-12, and lists the personal-data entities a future policy
  must cover.
- **AC-5 (negative) — Given** an attempt to enable the purge job without a registered policy,
  **When** it is made, **Then** startup or enablement fails, so the job can never run against an
  undefined policy and delete something arbitrary.
- **AC-6 (negative) — Given** a purge run is interrupted mid-batch, **When** it resumes, **Then** it
  is idempotent and no row is partially anonymised.

**Development Tasks**

**T-05.4.1.1 — Retention policy and anonymisation interfaces** · `P2` · `1 pts` · deps: `T-05.1.1.2`
- **Description:** Define the per-entity retention policy interface and the anonymisation strategy
  interface in `application`, with a registry that is empty by default, and document the personal-data
  entities a future policy must cover, citing TODO-12 and TODO-13.
- **Acceptance Criteria:** The registry is empty in every shipped profile; the documentation names
  both TODOs; no concrete policy exists in the codebase.
- **Dependencies:** T-05.1.1.2, T-05.3.1.3

**T-05.4.1.2 — Batched, idempotent purge job disabled by default** · `P2` · `1 pts` · deps: `T-05.4.1.1`
- **Description:** Implement the scheduled purge job operating in bounded, resumable batches, guarded
  so it refuses to start with an empty policy registry, disabled by configuration default in every
  profile, and never touching `vms.audit_logs`.
- **Acceptance Criteria:** The job is disabled by default; enabling it with no policy fails with a
  clear message; a test policy purges in batches idempotently across an interruption; audit rows are
  untouched.
- **Dependencies:** T-05.4.1.1

**T-05.4.1.3 — Purge audit and operator reporting** · `P2` · `1 pts` · deps: `T-05.4.1.2`
- **Description:** Emit a per-batch audit event and a summary recording the policy identity, matched
  and affected counts and duration, containing no purged personal data, and expose a run history to
  operators holding `report.view`.
- **Acceptance Criteria:** Each batch writes one audit row; no purged value appears in any audit
  field or log; run history is readable only with `report.view`.
- **Dependencies:** T-05.4.1.2, T-05.1.2.3

---

### F-05.5 — OWASP ASVS baseline verification in CI

Security verification as a pipeline gate, not a document.

`ENABLER` / OWASP · justified by NFR-SEC-01 (SRS B1) · P1 · 5 pts · 1 story · 3 tasks

#### US-05.5.1 — Verify the OWASP ASVS baseline in the pipeline

**As a** System Administrator **I want** the agreed OWASP ASVS Level 2 baseline verified
automatically on every build **so that** the security claims in this phase are continuously true
rather than true on the day they were reviewed.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `ENABLER` / OWASP ASVS — justified against NFR-SEC-01 (SRS B1); workflow doc §9 |
| **Dependencies** | US-01.4.1, US-03.2.1, US-05.2.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the agreed ASVS Level 2 control subset, **When** the baseline document is
  reviewed, **Then** each in-scope control is mapped to either an automated check, a manual review
  step with an owner, or a documented and justified exclusion.
- **AC-2 — Given** the pipeline, **When** it runs, **Then** automated security checks execute for
  authentication, session management, access control, input validation, error handling and logging
  controls, and each failure names the ASVS control identifier.
- **AC-3 — Given** the security headers baseline, **When** the portal is scanned, **Then**
  Content-Security-Policy, X-Content-Type-Options, Referrer-Policy and frame-ancestors restrictions
  are present and correct, and a missing header fails the build.
- **AC-4 — Given** a dependency or container base image with a newly published critical advisory,
  **When** the scheduled scan runs, **Then** it fails and raises an issue even with no code change,
  so the baseline degrades visibly rather than silently.
- **AC-5 (negative) — Given** a deliberately introduced regression — an unguarded endpoint, a
  disabled CSRF protection, a plaintext secret — **When** the pipeline runs, **Then** it fails and
  names the specific control, verified by a seeded regression test for each of the three cases.

**Development Tasks**

**T-05.5.1.1 — ASVS control baseline and mapping document** · `P1` · `2 pts` · deps: `T-01.4.1.3`
- **Description:** Select the ASVS Level 2 control subset applicable to Phase 1, map each control to
  an automated check, a manual review step with a named owner, or a justified exclusion, and commit
  it as the reviewable security baseline.
- **Acceptance Criteria:** Every in-scope control has exactly one disposition; exclusions carry a
  written justification; the document is referenced from the PR security checklist.
- **Dependencies:** T-01.4.1.3

**T-05.5.1.2 — Automated ASVS checks and security header verification** · `P1` · `2 pts` · deps: `T-05.5.1.1`
- **Description:** Implement the automated portion — the route authorization coverage test, the log
  redaction test, the sentinel secret test, the TLS and cipher assertions, and a security header scan
  against the running portal in the pipeline — with each failure reporting its ASVS control id.
- **Acceptance Criteria:** All checks run in CI as a required job; each failure names its control id;
  the three seeded regressions (unguarded endpoint, disabled protection, plaintext secret) each fail
  the build.
- **Dependencies:** T-05.5.1.1, T-03.2.1.3, T-05.2.1.2

**T-05.5.1.3 — Scheduled advisory rescan independent of code change** · `P2` · `1 pts` · deps: `T-05.5.1.2`
- **Description:** Add a scheduled pipeline run that rescans dependencies and container base images
  against current advisories and raises an issue on a new critical or high finding with no code
  change required to trigger it.
- **Acceptance Criteria:** The scheduled run executes on the agreed cadence; a newly published
  critical advisory produces a failing run and an issue; the issue names the advisory identifier and
  affected component.
- **Dependencies:** T-05.5.1.2, T-01.4.1.3

---

## EPIC-06 — Portal Shell & Design System

| | |
|---|---|
| **Provenance** | `SRS` / `SRS-CON` / `TDD-DERIVED` / `SRS-NFR` |
| **Requirement IDs** | FR-ADM-03 (SRS B1), CON-03 (SRS B1), NFR-USA-01 (SRS B1) |
| **Priority** | P1 |
| **Features** | 4 · **Stories** 5 · **Tasks** 15 · **Points** 23 |
| **Open questions** | TODO-01 (F-06.2, F-06.3 are TDD-derived), TODO-08 (navigation for the reception role depends on the account model) |

**Goal.** Deliver the Next.js portal shell that every later phase renders inside: a design token
system carrying the client's logo, colours and theme as FR-ADM-03 (SRS B1) and CON-03 (SRS B1)
require; a shared component library so Phase 2's request forms and Phase 3's reception screens are
not each invented from scratch; an authenticated, role-driven routing and navigation shell that
shows a user only what their permissions allow; and a WCAG 2.1 AA accessibility baseline verified in
CI. NFR-USA-01 (SRS B1) requires reception workflows to need minimal training — the accessibility
and consistency work here is the mechanism for that claim, though the AA target itself is derived
rather than stated, and is marked as such.

> **Security note.** The portal never makes an authorization decision that matters. Hiding a
> navigation item is a usability affordance; the API-boundary enforcement of F-03.2 is the control.
> Every role-driven UI story is tested against a corresponding server-side denial.

---

### F-06.1 — Design tokens & client branding

The branded interface FR-ADM-03 (SRS B1) requires, expressed as tokens rather than scattered styles.

`SRS` FR-ADM-03 (SRS B1), CON-03 (SRS B1) · P1 · 5 pts · 1 story · 3 tasks

#### US-06.1.1 — Branded design token system

**As a** Tenant **I want** the VMS portal to carry the client's logo, colours and theme **so that**
the interface is recognisably the client's, as FR-ADM-03 (SRS B1) and CON-03 (SRS B1) require.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS` FR-ADM-03 (SRS B1) — "branded interface (logo, colors, theme) consistent with the client's identity"; CON-03 (SRS B1) |
| **Dependencies** | US-01.6.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** the design token set, **When** I inspect it, **Then** colour, typography, spacing,
  radius, elevation and motion tokens are defined in one place and consumed by every component; no
  component declares a raw colour or font value.
- **AC-2 — Given** the client's brand assets, **When** the portal renders, **Then** the client logo
  appears in the application header and on the login screen, and the primary palette matches the
  supplied brand values.
- **AC-3 — Given** branding may change, **When** brand values are supplied by per-environment
  configuration, **Then** a logo or palette change requires a configuration change and no code
  change or component edit.
- **AC-4 — Given** the token set, **When** contrast is measured, **Then** every foreground/background
  token pairing used for text meets the WCAG 2.1 AA contrast ratio, so branding cannot silently
  break accessibility.
- **AC-5 (negative) — Given** a developer hard-codes a hex colour or a pixel font size in a
  component, **When** the lint rule runs, **Then** the build fails naming the file and value.
- **AC-6 (negative) — Given** a brand asset fails to load, **When** the portal renders, **Then** a
  neutral text fallback is shown and the layout does not collapse.

**Development Tasks**

**T-06.1.1.1 — Design token definition and theme provider** · `P1` · `2 pts` · deps: `T-01.6.1.1`
- **Description:** Define the token set as CSS custom properties with a typed TypeScript accessor,
  and implement the Next.js theme provider applying them at the application root, including a
  documented token naming convention.
- **Acceptance Criteria:** Tokens cover colour, typography, spacing, radius, elevation and motion;
  the provider applies them at the root; the accessor is typed so an unknown token is a compile error.
- **Dependencies:** T-01.6.1.1

**T-06.1.1.2 — Configuration-driven branding with contrast verification** · `P1` · `2 pts` · deps: `T-06.1.1.1`
- **Description:** Source logo asset reference and primary palette from per-environment configuration
  per F-01.6, render the logo in the header and login screen with an accessible text fallback, and
  add an automated contrast check over every text token pairing.
- **Acceptance Criteria:** Changing the configured palette changes the rendered theme with no code
  change; a failed asset load renders the text fallback; a pairing below the AA ratio fails the
  contrast check.
- **Dependencies:** T-06.1.1.1

**T-06.1.1.3 — Lint rule forbidding raw style values** · `P2` · `1 pts` · deps: `T-06.1.1.2`
- **Description:** Add a lint rule rejecting hard-coded colour, font-size and spacing literals in
  component source, with a narrow reviewed allowlist, wired into the CI lint job.
- **Acceptance Criteria:** A seeded hex literal fails the build naming the file; the allowlist is a
  single reviewed declaration; the rule runs in CI on every PR.
- **Dependencies:** T-06.1.1.2

---

### F-06.2 — Shared component library

The reusable component set every later phase builds its screens from.

`TDD-DERIVED` TDD §3 · P1 · 5 pts · 1 story · 3 tasks

#### US-06.2.1 — Shared, documented component library

**As a** Floor Receptionist **I want** every VMS screen built from the same components behaving the
same way **so that** the workflows require minimal training, as NFR-USA-01 (SRS B1) requires.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §3; justified against NFR-USA-01 (SRS B1) |
| **Dependencies** | US-06.1.1 |
| **Blocked by** | TODO-01 — the component library itself is TDD-derived. Scope is limited to components Phase 1 screens actually need (user administration, role administration, master data CRUD, audit query); no speculative component for an unbuilt Phase 2 screen. |

**Acceptance Criteria**
- **AC-1 — Given** the library, **When** I inspect it, **Then** it provides form inputs with
  validation display, buttons, a data table with server-side pagination, sorting and filtering,
  modal and confirmation dialogs, toast notifications, an empty state, a loading state and an error
  state — each consuming design tokens only.
- **AC-2 — Given** a form component, **When** a field fails validation, **Then** the error is
  displayed adjacent to the field, is programmatically associated with the input, and focus moves to
  the first invalid field on submit.
- **AC-3 — Given** the data table, **When** it is used, **Then** pagination, sorting and filtering
  are server-driven so a 121-user or large master data list is never fully loaded into the browser.
- **AC-4 — Given** each component, **When** the library documentation is built, **Then** every
  component has a rendered example with its props documented and its accessible name behaviour
  described.
- **AC-5 (negative) — Given** an API call fails or times out, **When** a component renders the
  result, **Then** the error state shows a user-appropriate message and the correlation id, and never
  a raw server payload, stack trace or internal identifier.
- **AC-6 (negative) — Given** a destructive action such as deactivation, **When** it is triggered,
  **Then** a confirmation dialog naming the specific record is required, and the confirm control is
  not the default focused element.

**Development Tasks**

**T-06.2.1.1 — Core form, action and feedback components** · `P1` · `2 pts` · deps: `T-06.1.1.1`
- **Description:** Build the form input set with validation display and focus management, buttons,
  modal and confirmation dialogs with focus trapping and restoration, and toast notifications — all
  consuming design tokens only.
- **Acceptance Criteria:** Validation errors are programmatically associated with their inputs; focus
  moves to the first invalid field on submit; dialogs trap focus and restore it on close; no raw
  style value appears in any component.
- **Dependencies:** T-06.1.1.1

**T-06.2.1.2 — Server-driven data table and state components** · `P1` · `2 pts` · deps: `T-06.2.1.1`
- **Description:** Build the data table with server-side pagination, sorting and filtering bound to
  the API contract, plus empty, loading and error state components rendering a safe message and the
  correlation id.
- **Acceptance Criteria:** A large list paginates without full client load; sort and filter changes
  issue server requests; the error state never renders a raw payload or stack trace; the correlation
  id is displayed for support.
- **Dependencies:** T-06.2.1.1

**T-06.2.1.3 — Component documentation and visual regression baseline** · `P2` · `1 pts` · deps: `T-06.2.1.2`
- **Description:** Publish a component catalogue with rendered examples and documented props, and
  capture a visual regression baseline run in CI.
- **Acceptance Criteria:** Every component has a documented example; the catalogue builds in CI; an
  unintended visual change fails the regression check with a diff artefact.
- **Dependencies:** T-06.2.1.2

---

### F-06.3 — Role-driven routing & navigation shell

Authenticated routing, session handling in the browser, and navigation driven by permissions.

`TDD-DERIVED` TDD §3 · P1 · 8 pts · 2 stories · 6 tasks

#### US-06.3.1 — Authenticated application shell and route protection

**As a** Tenant **I want** the portal to require login and keep my session alive transparently **so
that** I reach my work without re-authenticating constantly, and an unauthenticated visitor reaches
nothing.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `TDD-DERIVED` TDD §3; consumes FR-ADM-02 (SRS B1) authentication |
| **Dependencies** | US-02.1.1, US-02.1.2, US-06.2.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** I am unauthenticated, **When** I request any application route other than login,
  **Then** I am redirected to login with the requested path preserved and returned to it after
  successful authentication.
- **AC-2 — Given** I am authenticated, **When** my access token nears expiry during use, **Then** it
  is refreshed transparently using the refresh token and my in-progress work is not interrupted.
- **AC-3 — Given** tokens are held in the browser, **When** I inspect the storage mechanism, **Then**
  the refresh token is not readable by page JavaScript, and no token value is written to
  `localStorage`, a URL, a query string or the browser history.
- **AC-4 — Given** I log out, **When** the action completes, **Then** the server session is revoked,
  all client-side session state is cleared, and pressing back does not render cached authenticated
  content.
- **AC-5 (negative) — Given** my session has been revoked server-side (deactivated account, replay
  detection), **When** my next request returns 401, **Then** I am returned to login with a neutral
  message and all client state is cleared, with no retry loop.
- **AC-6 (negative) — Given** a refresh attempt fails, **When** it fails, **Then** exactly one
  refresh is in flight at a time across concurrent requests, and failure results in a single
  redirect to login rather than a storm of retries.

**Development Tasks**

**T-06.3.1.1 — Next.js application shell and route protection** · `P1` · `2 pts` · deps: `T-06.2.1.2`
- **Description:** Build the authenticated layout shell with header, navigation region and content
  region, and route protection redirecting unauthenticated requests to login while preserving the
  intended path.
- **Acceptance Criteria:** Every non-login route requires authentication; the intended path is
  restored after login; the shell composes the F-06.1 theme and F-06.2 components only.
- **Dependencies:** T-06.2.1.2

**T-06.3.1.2 — Token handling and transparent refresh** · `P1` · `2 pts` · deps: `T-06.3.1.1`
- **Description:** Implement browser session handling with the refresh token held in an HttpOnly,
  Secure, SameSite cookie, the access token kept in memory only, and a single-flight refresh
  interceptor that queues concurrent requests behind one refresh attempt.
- **Acceptance Criteria:** No token appears in `localStorage`, a URL or history; concurrent 401s
  trigger exactly one refresh; a failed refresh produces one redirect to login and clears state.
- **Dependencies:** T-06.3.1.1, T-02.1.2.2

**T-06.3.1.3 — Logout, revocation handling and cache clearing** · `P1` · `1 pts` · deps: `T-06.3.1.2`
- **Description:** Implement logout invoking server revocation, clearing client caches and query
  state, and handling a server-side revocation encountered mid-session with a neutral message.
- **Acceptance Criteria:** After logout, back navigation renders no authenticated content; a
  revoked session redirects once with a neutral message; no cached response survives logout.
- **Dependencies:** T-06.3.1.2, T-02.1.2.3

#### US-06.3.2 — Permission-driven navigation

**As a** Floor Receptionist **I want** to see only the navigation and actions my permissions allow
**so that** the interface stays simple and I am not offered work I cannot do — supporting the minimal
training NFR-USA-01 (SRS B1) requires.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 3 |
| **Provenance** | `TDD-DERIVED` TDD §3; justified against NFR-USA-01 (SRS B1) and NFR-SEC-01 (SRS B1) |
| **Dependencies** | US-06.3.1, US-03.1.1 |
| **Blocked by** | TODO-08 — the reception navigation depends on whether a reception account is personal or shared. Navigation is driven purely by the principal's effective permissions, which is correct under any of the three readings. |

**Acceptance Criteria**
- **AC-1 — Given** my effective permission set is delivered with my session, **When** the navigation
  renders, **Then** only items whose required permission I hold are shown, and an item is never
  rendered disabled as a hint that a capability exists.
- **AC-2 — Given** I hold `user.manage` and `settings.manage`, **When** I navigate, **Then** the
  administration section is available; a principal holding neither sees no administration entry
  point at all.
- **AC-3 — Given** my permissions change while I am logged in, **When** my session next refreshes,
  **Then** the navigation reflects the new permission set without requiring a full re-login.
- **AC-4 — Given** navigation is a usability affordance only, **When** I request a route directly by
  URL without the required permission, **Then** the page shell may render but every API call it makes
  is denied with 403 by F-03.2, and the page renders the access-denied state.
- **AC-5 (negative) — Given** a user with an empty effective permission set, **When** they log in,
  **Then** they see a clear no-access state rather than an empty broken shell, and an audit event
  records the condition for the administrator to act on.
- **AC-6 (negative) — Given** the permission set is delivered to the browser, **When** a user
  modifies it in client state, **Then** additional navigation may appear but every corresponding API
  call is still denied server-side, verified by an explicit test.

**Development Tasks**

**T-06.3.2.1 — Session permission delivery and client permission context** · `P1` · `1 pts` · deps: `T-06.3.1.2`
- **Description:** Deliver the effective permission set with the session payload and expose it
  through a client context, refreshed alongside the access token, with permission codes referenced
  through a shared typed constant set aligned to the server catalogue.
- **Acceptance Criteria:** The permission set is available to every component; it refreshes with the
  session; an unknown permission code is a compile error; the payload contains no personal data
  beyond display name.
- **Dependencies:** T-06.3.1.2, T-03.1.1.4

**T-06.3.2.2 — Permission-driven navigation and action visibility** · `P1` · `1 pts` · deps: `T-06.3.2.1`
- **Description:** Implement navigation and action-level visibility bound to required permission
  codes, with items hidden rather than disabled, and route-level access-denied and no-access states.
- **Acceptance Criteria:** Each seeded role sees only its permitted navigation, asserted per role by
  test; an empty permission set renders the no-access state; direct URL access renders the
  access-denied state.
- **Dependencies:** T-06.3.2.1

**T-06.3.2.3 — Client-tamper test proving server-side enforcement** · `P1` · `1 pts` · deps: `T-06.3.2.2`
- **Description:** Add an end-to-end test that injects elevated permissions into client state and
  asserts every corresponding API call is still denied with 403 and audited, documenting that UI
  visibility is never the control.
- **Acceptance Criteria:** The test elevates client permissions, exercises the revealed actions, and
  asserts 403 plus a denial audit row for each; the test runs in the pipeline.
- **Dependencies:** T-06.3.2.2, T-03.2.2.2

---

### F-06.4 — Accessibility baseline (WCAG 2.1 AA)

An automated and manual accessibility floor, verified in CI.

`SRS-NFR` NFR-USA-01 (SRS B1) *(WCAG 2.1 AA target is derived, not stated in SRS B1)* · P1 · 5 pts · 1 story · 3 tasks

#### US-06.4.1 — WCAG 2.1 AA accessibility baseline

**As a** Floor Receptionist **I want** the portal to be operable by keyboard and legible to assistive
technology **so that** reception staff can work quickly and without barriers, supporting the minimal
training NFR-USA-01 (SRS B1) requires.

| | |
|---|---|
| **Priority** | P1 |
| **Story Points** | 5 |
| **Provenance** | `SRS-NFR` NFR-USA-01 (SRS B1); **WCAG 2.1 AA is a derived target** — the SRS states usability but names no standard. Recorded as a provisional decision for client confirmation. |
| **Dependencies** | US-06.2.1, US-06.3.1 |
| **Blocked by** | — |

**Acceptance Criteria**
- **AC-1 — Given** every Phase 1 screen, **When** the automated accessibility scan runs in CI,
  **Then** it reports zero critical and zero serious violations, and the job fails on any new one.
- **AC-2 — Given** any workflow in the portal, **When** it is performed using only a keyboard,
  **Then** every interactive element is reachable in a logical order, focus is always visible, and no
  keyboard trap exists outside an intentional modal that can be dismissed with Escape.
- **AC-3 — Given** a screen reader, **When** it traverses a page, **Then** landmarks, headings in
  correct hierarchical order, form labels, table headers and button accessible names are present and
  meaningful.
- **AC-4 — Given** an asynchronous outcome such as a save or a validation failure, **When** it
  occurs, **Then** it is announced through a live region rather than being conveyed by colour or
  position alone.
- **AC-5 (negative) — Given** a status or error is conveyed by colour, **When** it is reviewed,
  **Then** it also carries text or an icon with an accessible name, so meaning does not depend on
  colour perception.
- **AC-6 (negative) — Given** a developer introduces an image without alternative text or a control
  without an accessible name, **When** CI runs, **Then** the accessibility job fails naming the
  element and the WCAG success criterion.

**Development Tasks**

**T-06.4.1.1 — Semantic structure, landmarks and keyboard operability** · `P1` · `2 pts` · deps: `T-06.3.1.1`
- **Description:** Apply semantic landmarks, a correct heading hierarchy, a skip-to-content link,
  visible focus styling from design tokens, logical tab order, and Escape-dismissible modals with
  focus restoration across the shell and every Phase 1 screen.
- **Acceptance Criteria:** Every workflow completes by keyboard alone; focus is visible on every
  interactive element; no keyboard trap exists; landmarks and heading order are correct on each page.
- **Dependencies:** T-06.3.1.1, T-06.2.1.1

**T-06.4.1.2 — Accessible names, live regions and non-colour status cues** · `P1` · `2 pts` · deps: `T-06.4.1.1`
- **Description:** Ensure every control, image and table has an accessible name, announce
  asynchronous outcomes through live regions, and pair every colour-coded status with text or a
  named icon.
- **Acceptance Criteria:** A screen reader announces each save and validation outcome; no status is
  colour-only; every image has appropriate alternative text or is correctly marked decorative.
- **Dependencies:** T-06.4.1.1

**T-06.4.1.3 — Automated accessibility gate and manual review checklist** · `P1` · `1 pts` · deps: `T-06.4.1.2`
- **Description:** Wire an automated accessibility scan across all Phase 1 routes into CI as a
  required check reporting the WCAG success criterion for each finding, and commit a manual review
  checklist covering the criteria automation cannot verify.
- **Acceptance Criteria:** The job fails on any new critical or serious violation and names the
  criterion; a seeded unlabelled control fails the build; the manual checklist is committed and
  referenced from the PR template.
- **Dependencies:** T-06.4.1.2, T-01.4.1.1

---

## Phase 1 summary

### Per-epic totals

| Epic | Title | Provenance | Features | Stories | Tasks | Points | Priority |
|---|---|---|---|---|---|---|---|
| EPIC-01 | Engineering Platform & Delivery Pipeline | `ENABLER` | 7 | 9 | 27 | 38 | P0 |
| EPIC-02 | Identity, Authentication & Session Management | `SRS` / `SRS-NFR` | 4 | 6 | 19 | 26 | P0 |
| EPIC-03 | Authorization & RBAC | `TDD-DERIVED` / `SRS-NFR` / `BLOCKED` | 4 | 5 | 16 | 21 | P0 |
| EPIC-04 | Configuration & Master Data | `TDD-DERIVED` ⚠️ | 9 | 11 | 31 | 33 | P1 |
| EPIC-05 | Audit, Security & Compliance Foundation | `TDD-DERIVED` / `SRS` / `SRS-NFR` / `BLOCKED` / `ENABLER` | 5 | 6 | 19 | 26 | P0 |
| EPIC-06 | Portal Shell & Design System | `SRS` / `TDD-DERIVED` / `SRS-NFR` | 4 | 5 | 15 | 23 | P1 |
| **Total** | | | **33** | **42** | **127** | **167** | |

### Points by priority

| Priority | Stories | Points | Share |
|---|---|---|---|
| P0 | 20 | 88 | 53% |
| P1 | 19 | 71 | 42% |
| P2 | 3 | 8 | 5% |
| P3 | 0 | 0 | 0% |

### Blocked and conditional work

| Story | Blocked by | Effect |
|---|---|---|
| US-02.1.1, US-02.3.1 | TODO-15 | Local-account path built behind an authentication-provider port; a federation decision becomes an adapter, not a rewrite |
| US-02.2.1, US-02.2.2 | TODO-08 | Named-account model built; no per-floor count rule and no licence ceiling encoded |
| US-03.1.1, US-03.3.1 | TODO-01 | Constrained to the five seeded roles and eleven seeded permissions; no new roles or permission codes |
| US-03.4.1 | TODO-14 | Scoping seam with a **deny-all** default; no isolation policy chosen |
| All 11 EPIC-04 stories | TODO-01 | Backlog only; endpoints ship behind a feature flag defaulting off |
| US-05.1.1, US-05.1.2 | TODO-01 | Built as P0 on the NFR-SEC-01 (SRS B1) justification; flagged as a disposition priority |
| US-05.4.1 | TODO-12 | Framework only, purge disabled, empty policy registry |
| US-06.2.1, US-06.3.2 | TODO-01 / TODO-08 | Scope limited to Phase 1 screens; navigation driven purely by effective permissions |

**33 of 42 stories (79%) carry a `Blocked by` value.** That is the honest shape of this phase given
TODO-01 and TODO-02 remain open, and it is the single most important thing for the client to see.

### SRS requirement coverage delivered in Phase 1

| Requirement | Where |
|---|---|
| FR-ADM-01 (SRS B1) | F-02.4 / US-02.4.1 |
| FR-ADM-02 (SRS B1) | F-02.1 / US-02.1.1, US-02.1.2 · F-02.2 / US-02.2.1, US-02.2.2 |
| FR-ADM-03 (SRS B1) | F-06.1 / US-06.1.1 |
| FR-API-03 (SRS B1) | F-05.2 / US-05.2.1 *(partial — the ACS-side use lands with F-11.6 in Phase 3)* |
| NFR-SEC-01 (SRS B1) | F-02.3, F-03.2, F-05.5 |
| NFR-CMP-01 (SRS B1) | F-05.3, F-05.4 *(F-05.4 blocked on TODO-12)* |
| NFR-MNT-01 (SRS B1) | F-01.2 architecture fitness tests — the mechanical guarantee |
| NFR-SCL-01 (SRS B1) | F-04.9 caching, F-01.6 autoscaling, F-02.1 session store at 120 concurrent |
| NFR-AVL-01 (SRS B1) | F-01.7 probes, F-01.3 degraded-dependency startup |
| NFR-USA-01 (SRS B1) | F-06.2, F-06.4 |
| CON-03 (SRS B1) | F-06.1 |

### Pull-forward interface with Phase 3

F-11.1 (ACS port definition) and F-11.2 (ACS simulator) are deliberately pulled forward per the epic
index and ADR-0002. Phase 1 does not decompose them, but it does prepare their landing site:

- **T-01.2.1.2** creates the `acs` bounded-context package as the port's only permitted home.
- **T-01.2.2.2** installs the fitness rule that no ACS-specific type may exist outside it — the
  standing mitigation for TODO-02.
- **T-05.2.1.1** provisions the ACS service credential through the secrets mechanism, satisfying
  FR-API-03 (SRS B1) ahead of the adapter that will use it.

### Epic-level dependency graph

```
                            ┌──────────────────────────────────────────┐
                            │  EPIC-01  Engineering Platform  (P0)     │
                            │  repo · architecture · CI · migrations   │
                            │  containers · observability              │
                            └────────────────────┬─────────────────────┘
                                                 │ everything depends on this
                    ┌────────────────────────────┼────────────────────────────┐
                    ▼                            ▼                            ▼
       ┌────────────────────────┐   ┌────────────────────────┐   ┌────────────────────────┐
       │ EPIC-02  Identity (P0) │──▶│ EPIC-03  AuthZ/RBAC(P0)│   │ EPIC-05  Audit &       │
       │ login · JWT · sessions │   │ roles · deny-by-default│◀──│ Security  (P0)         │
       │ users · policy · admin │◀──│ grants · scoping seam  │──▶│ audit · secrets · TLS  │
       └───────────┬────────────┘   └───────────┬────────────┘   └───────────┬────────────┘
                   │                            │                            │
                   │   (mutual: identity writes audit; authz reads roles;     │
                   │    audit records every authz denial)                     │
                   │                            │                            │
                   └────────────┬───────────────┴────────────────────────────┘
                                ▼
                  ┌──────────────────────────────────────────┐
                  │ EPIC-04  Configuration & Master Data (P1)│
                  │ ⚠️ TDD-DERIVED — backlog only, TODO-01   │
                  │ buildings · floors · tenants · receptions│
                  │ visitor/pass types · holidays · settings │
                  │ cache + event-driven invalidation        │
                  └────────────────────┬─────────────────────┘
                                       │
                                       ▼
                  ┌──────────────────────────────────────────┐
                  │ EPIC-06  Portal Shell & Design System(P1)│
                  │ tokens/branding · components · routing   │
                  │ role-driven nav · WCAG 2.1 AA baseline   │
                  └────────────────────┬─────────────────────┘
                                       │
                                       ▼
                            ═══════════════════════
                              PHASE 2  (EPIC-07..10)
                              visitor requests, approval,
                              pre-registration, passes
                            ═══════════════════════

Critical path:   EPIC-01 ──▶ EPIC-02 ──▶ EPIC-03 ──▶ EPIC-05 ──▶ (EPIC-04) ──▶ EPIC-06

Notes on the ordering
  • EPIC-05's audit writer (T-05.1.1.2) is needed by EPIC-02 and EPIC-03; F-05.1 must start
    early even though the rest of EPIC-05 can trail. Treat F-05.1 as part of the EPIC-02 wave.
  • EPIC-02 ⇄ EPIC-03 is genuinely mutual, not a cycle in practice: US-03.1.1 (role model)
    precedes US-02.2.1 (user admin) and US-02.4.1; US-03.2.1 (enforcement) follows US-02.1.1
    (token issuance). Sequence at story level, not epic level.
  • EPIC-04 gates only on TODO-01 disposition, not on technical readiness. If TODO-01 resolves
    against building it, EPIC-06's master data screens shrink and the phase ends earlier.
  • EPIC-06 depends on EPIC-02/03 for authentication and permissions and on EPIC-04 for the
    screens it renders; F-06.1 and F-06.2 can start in parallel with EPIC-02.

Pull-forward from Phase 3 (per epic index and ADR-0002)
  F-11.1 ACS port + F-11.2 simulator ──▶ land in Phase 1's `acs` module skeleton
  (T-01.2.1.2 package, T-01.2.2.2 boundary rule, T-05.2.1.1 service credential)
```

---

## Document control

| | |
|---|---|
| **Phase** | 1 — Platform Foundation |
| **Baseline** | B1 |
| **Contract** | [`00-epic-feature-index.md`](00-epic-feature-index.md) — 6 epics, 33 features, decomposed exactly |
| **Governing docs** | [`../01-requirements-catalogue.md`](../01-requirements-catalogue.md) · [`../05-workflow-and-branching.md`](../05-workflow-and-branching.md) · [`../07-open-questions.md`](../07-open-questions.md) |
| **Schema** | `docs/vms_schema_postgresql.sql` |

**Reverse-trace rule.** At each sprint review, every story closed in the sprint is traced back to its
feature, epic and requirement. A `TDD-DERIVED` story that closed without a TODO-01 disposition is a
process failure and is reopened.
