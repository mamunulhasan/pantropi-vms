# Development Workflow, Branching & Conventions

**Audience:** everyone contributing to Project Pinnacle VMS.

---

## 1. The traceability chain

```
Requirement (SRS)
  └─ GitHub Milestone
      └─ Epic          (issue, type/epic)
          └─ Feature   (issue, type/feature)
              └─ User Story (issue, type/user-story)   ◄── the unit of work
                  └─ Task   (issue, type/task)
                      └─ Code
                          └─ Unit Test
                              └─ Integration Test
                                  └─ Pull Request
                                      └─ Merge
                                          └─ Release
```

Every link is enforced by an issue template field or a PR checklist item. A break anywhere in the
chain fails review.

---

## 2. Branching model

`git-flow`, feature branches only. **Nothing is committed directly to `develop` or `main`.**

| Branch | Purpose | Protected | Merges from | Merges to |
|---|---|---|---|---|
| `main` | Production. Every commit is a tagged release. | ✅ | `release/*`, `hotfix/*` | — |
| `develop` | Integration. Always deployable to staging. | ✅ | `feature/*`, `bugfix/*`, `release/*`, `hotfix/*` | `release/*` |
| `feature/*` | One user story. | — | `develop` | `develop` |
| `bugfix/*` | One defect on `develop`. | — | `develop` | `develop` |
| `release/*` | Release stabilisation. | — | `develop` | `main` + `develop` |
| `hotfix/*` | Urgent production fix. | — | `main` | `main` + `develop` |

### Naming

```
feature/US-04.1.1-tenant-submit-visitor-request
bugfix/123-approval-dashboard-pagination
release/0.2.0
hotfix/0.2.1-credential-expiry-timezone
spike/TODO-02-acs-client-interface
```

Feature branches carry the **user story id**. This is what makes `git log` traceable back to a
requirement without opening GitHub.

### Branch protection (configure before first merge)

On `main` and `develop`:
- Require a pull request; **no direct pushes**
- Require ≥1 approving review (≥2 for `area/acs-integration` and `security`)
- Require CODEOWNERS review
- Require status checks: build, unit tests, integration tests, coverage gate, SAST, dependency scan,
  commit-message lint, architecture fitness tests
- Require branches to be up to date before merging
- Require conversation resolution
- Dismiss stale approvals on new commits
- Include administrators

> 🔧 **TODO:** CODEOWNERS uses placeholder team handles (`@pantropi/vms-*`). Replace with real teams
> before enabling protection, or reviews will not be requestable.

---

## 3. Conventional Commits

```
<type>(<scope>): <subject> (<user-story-id>)

<body>

<footer>
```

**Types:** `feat` · `fix` · `docs` · `test` · `refactor` · `perf` · `build` · `ci` · `chore` · `revert`

**Scopes** (align to TDD §4 services): `master-data` · `visitor` · `credential` · `entry` · `acs` ·
`notification` · `reporting` · `identity` · `audit` · `frontend` · `platform` · `db`

### Examples

```
feat(visitor): submit visitor entry request (US-04.1.1)

Tenants can submit a visitor entry request with visitor details and a
requested time window. The request enters `submitted` status and appears
on the FM Admin approval queue.

Requirement: FR-VMS-01 (SRS B1)
Closes #56
```

```
fix(acs): retry queue no longer drops a request on dead-letter (US-12.3.1)

A request that exhausted retries was removed from the outbox before the
dead-letter record was written, losing it on a crash between the two.
Both now happen in one transaction.

Requirement: FR-API-01 (SRS B1)
Fixes #201
```

```
feat(acs)!: replace ACS port credential DTO with the UAL contract shape

BREAKING CHANGE: AcsCredentialRequest field names now follow the published
UAL contract. Callers using the provisional shape must be updated.

Requirement: FR-VMS-05 (SRS B1)
Refs TODO-02
```

**Rules**
- Subject: imperative mood, lowercase, no trailing period, ≤72 chars
- Every `feat` and `fix` commit names its user story id
- Every commit body naming a requirement uses the qualified form — see D-02:
  - `FR-VMS-01 (SRS B1)` when the SRS appendix defines it
  - `FR-SET-01 (TDD-derived)` when it appears only in the TDD or the published schema. Do **not**
    write `(SRS B1)` for these: the id has no SRS definition, and claiming one is exactly the
    ambiguity this rule exists to prevent (TODO-01, ADR-0004)
- Breaking changes use `!` and a `BREAKING CHANGE:` footer

Release notes and the changelog are generated from this history. A sloppy commit message is a hole
in the audit trail.

---

## 4. Sequencing rules

These are project rules, restated as workflow:

1. **One user story at a time, per developer.** Before pulling a story, confirm you have no other
   story in `status/in-progress`. A half-finished story is inventory, not progress.
2. **Never modify unrelated code.** Noticed something broken outside your story? Raise an issue.
   Do not fix it in this PR — it destroys the reviewability of the change and breaks the traceability
   claim that this diff implements exactly one requirement.
3. **Every change has tests.** Unit tests for domain and application logic; integration tests for
   every acceptance criterion.
4. **Every PR updates documentation.** At minimum, the traceability matrix delivery log.
5. **Every feature is independently deployable.** Behind a flag, backward compatible, rollback-able.

---

## 5. Definition of Ready (user story)

A story may not enter a sprint unless **all** hold:

- [ ] Traces to an SRS requirement through a feature and an epic
- [ ] Acceptance criteria written as Given/When/Then and independently testable
- [ ] Negative and edge cases identified
- [ ] Security considerations recorded (authorisation, PII, audit events)
- [ ] No **blocking** TODO applies, or the blocked portion is explicitly carved out
- [ ] Dependencies on other stories identified and sequenced
- [ ] Estimated by the team
- [ ] Adds no behaviour beyond the stated requirement

---

## 6. Definition of Done (user story)

- [ ] All acceptance criteria demonstrated
- [ ] Unit tests written, passing, meaningful — not coverage theatre
- [ ] Integration tests cover every acceptance criterion and the negative cases
- [ ] Coverage has not regressed
- [ ] Architecture fitness tests pass — no layer or ACS-boundary violation
- [ ] Code reviewed and approved by a CODEOWNER
- [ ] Security checklist addressed
- [ ] Documentation updated; traceability matrix delivery log row added
- [ ] Conventional Commits throughout
- [ ] Feature flag in place; deployed to staging and verified
- [ ] No unrelated code modified

---

## 7. Clean Architecture boundaries

Dependencies point **inward only**. Enforced by automated fitness tests in CI, not by convention.

```
┌─────────────────────────────────────────────────────┐
│ interfaces      REST controllers, schedulers, UI    │
│  ┌────────────────────────────────────────────────┐ │
│  │ infrastructure  persistence, ACS adapter,      │ │
│  │                 email, Kafka, Redis            │ │
│  │  ┌───────────────────────────────────────────┐ │ │
│  │  │ application   use cases, ports,           │ │ │
│  │  │               orchestration               │ │ │
│  │  │  ┌──────────────────────────────────────┐ │ │ │
│  │  │  │ domain   entities, value objects,    │ │ │ │
│  │  │  │          aggregates, domain events   │ │ │ │
│  │  │  │          ── NO framework imports ──  │ │ │ │
│  │  │  └──────────────────────────────────────┘ │ │ │
│  │  └───────────────────────────────────────────┘ │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

**Non-negotiable rules**

| Rule | Enforces |
|---|---|
| `domain` imports nothing from `application`, `infrastructure`, or `interfaces` | Clean Architecture |
| `domain` imports no framework (no Spring, no JPA annotations) | Testability, NFR-MNT-01 |
| `application` defines **ports**; `infrastructure` implements **adapters** | Dependency inversion |
| **No ACS-specific type exists outside the ACS integration module** | CON-01, CON-02, NFR-MNT-01 |
| No service reaches another service's tables directly | Service boundaries, TDD §4 |

That fourth rule is the one that matters most on this project. The entire mitigation for the missing
ACS API contract (TODO-02) rests on ACS specifics being swappable in one place.

---

## 8. Bounded contexts (DDD)

Aligned to TDD §4. Each owns its data; cross-context communication is by domain event or an explicit
port — never a shared table.

| Context | Aggregate roots | Owns |
|---|---|---|
| Configuration & Master Data | Building, Tenant, PassType | Reference data |
| Visitor & Approval | VisitorRequest *(root)*, Visitor | The pre-arrival journey |
| Pass & Credential | Credential | Credential lifecycle, ACS credential reference |
| Entry & ACS Operations | Visit, CardIssuance | Live visit lifecycle, access events |
| Notification | Notification | Dispatch and delivery status |
| Reporting | *(read models only)* | Projections — no writes to other contexts |
| Identity & Audit | User, Role, AuditEntry | AuthN/AuthZ, append-only audit |

**Consistency rule:** one aggregate per transaction. Cross-aggregate consistency is eventual, via
domain events.

---

## 9. Testing strategy

| Level | Scope | Runs |
|---|---|---|
| Unit | Domain logic, use cases. No I/O, no Spring context. | Every commit |
| Integration | Use case through real persistence (Testcontainers PostgreSQL) | Every commit |
| Contract | ACS port ↔ simulator; consumer-driven once TODO-02 lands | Every commit |
| Architecture fitness | Layer rules, ACS-boundary rule | Every commit |
| End-to-end | Critical journeys: request→approve→issue→enter→exit | Nightly + pre-release |
| Performance | NFR-PRF-01, NFR-SCL-01 | Pre-release |
| Security | SAST, dependency scan, OWASP ASVS review | Every commit / pre-release |

**Every acceptance criterion maps to at least one integration test.** That mapping is what lets the
traceability matrix claim a requirement is verified rather than merely implemented.

---

## 10. Handling ambiguity

When a requirement does not tell you what to build:

1. **Stop.** Do not choose an interpretation and proceed.
2. Check [`07-open-questions.md`](07-open-questions.md) — it may already be raised.
3. If not, open a **Spike / Clarification** issue, label `needs-clarification`, and add it to the
   open-questions register.
4. Label the affected story `status/blocked` and carve out the unblocked portion if any.
5. If a decision is unavoidable to keep moving, record it as an **ADR** marked *provisional*, state
   the assumption explicitly, and flag it for client confirmation.

A guess that ships silently becomes a requirement nobody agreed to.
