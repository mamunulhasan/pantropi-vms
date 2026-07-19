# GitHub Labels & Project Board

Canonical label definitions live in [`.github/labels.yml`](../../.github/labels.yml) and are applied
by [`scripts/bootstrap-github.sh`](../../scripts/bootstrap-github.sh).

---

## 1. Label taxonomy

Six sets. Four are the required categories; two are project-specific controls that exist because of
this project's traceability rules and external dependencies.

| Set | Cardinality | Purpose |
|---|---|---|
| **Type** | exactly 1 | What kind of work item |
| **Priority** | exactly 1 | Delivery urgency |
| **Status** | exactly 1 | Board position |
| **Component** | 1 or more | Architectural area |
| *Provenance* | exactly 1 | **What authorises the work** — traceability control |
| *Blocker* | 0 or more | Why it is blocked |
| *Cross-cutting* | 0 or more | Phase, ACS stage, security, PII, NFR |

### Type

| Label | Colour | Meaning |
|---|---|---|
| `type/epic` | 🟣 `5319E7` | Large body of work traceable to SRS requirements |
| `type/feature` | 🟣 `7B42BC` | Independently deployable slice of an epic |
| `type/user-story` | 🟢 `0E8A16` | **The unit of work** — one at a time |
| `type/task` | 🔵 `1D76DB` | Concrete implementation work |
| `type/bug` | 🔴 `D73A4A` | Delivered behaviour breaches a requirement |
| `type/spike` | 🟡 `FBCA04` | Time-boxed investigation or clarification |
| `type/enabler` | 🔵 `BFD4F2` | Technical work with no direct requirement |

### Priority

| Label | Meaning | Sprint rule |
|---|---|---|
| `priority/P0-critical` | Blocks the phase or critical path | Pulled first, always |
| `priority/P1-high` | Core requirement for phase acceptance | Pulled before any P2 |
| `priority/P2-medium` | Required, but the phase can demo without it | Fills remaining capacity |
| `priority/P3-low` | Deferrable to a later release | Only if capacity remains |

### Status — mirrors the board

`status/backlog` · `status/ready` · `status/in-progress` · `status/code-review` · `status/testing` ·
`status/ready-for-staging` · `status/done` · `status/blocked`

Status labels and board columns are kept in sync by automation (§3). `status/blocked` is orthogonal —
an issue keeps its column label *and* gains `status/blocked`, so you can see both where it sits and
that it is stuck.

### Component — maps to TDD §4 and the module layout

| Label | TDD service |
|---|---|
| `component/platform` | Build, CI/CD, infrastructure, observability |
| `component/identity` | Authentication, sessions, users |
| `component/rbac` | Roles, permissions, authorization |
| `component/master-data` | §4.1 Configuration & Master Data |
| `component/visitor` | §4.2 Visitor & Approval |
| `component/credential` | §4.3 Pass & Credential |
| `component/entry` | §4.4 Entry & ACS Operations |
| `component/acs-integration` | §6 ACS Integration Client — the choke-point |
| `component/card` | Card issuance, return, reconciliation |
| `component/notification` | §8 Notification Service |
| `component/reporting` | §4.5 Reporting & Analytics |
| `component/analytics` | Dashboard and exports |
| `component/frontend` | Portal, reception UI, visitor display |
| `component/database` | Schema, migrations |
| `component/audit` | Audit log, security, compliance |

### Provenance — the traceability control

This set is why the backlog cannot quietly drift beyond the requirements.

| Label | Meaning | Implementable? |
|---|---|---|
| `provenance/srs` | Backed by a numbered SRS requirement | ✅ Yes |
| `provenance/tdd-derived` | From the TDD, **no** SRS requirement behind it | ⚠️ Backlog only — needs TODO-01 |
| `provenance/enabler` | Technical necessity justified against an NFR | ✅ Yes |
| `provenance/unbacked` | Nothing authorises this | ⛔ **Must not be implemented** |

> **A `provenance/tdd-derived` issue moving to `status/in-progress` without a TODO-01 disposition is
> a process failure.** The sprint-review reverse trace exists to catch exactly this.

### Blocker & cross-cutting

`blocked/srs-v2` · `blocked/acs-contract` · `blocked/client-decision` · `blocked/vendor-decision` ·
`blocked/upstream` · `needs-clarification`

`phase/1-platform-foundation` … `phase/4-reporting-notification`
`acs-stage/A-simulator` · `acs-stage/B-real-contract`
`security` · `privacy/pii` · `nfr` · `descope-candidate` · `documentation` · `technical-debt` · `trace/verified`

---

## 2. Project board

**Board:** *Project Pinnacle VMS — Delivery*
**Type:** GitHub Projects (v2), board layout.

### Columns

| # | Column | Entry criteria | Exit criteria | WIP limit |
|---|---|---|---|---|
| 1 | **Backlog** | Issue created and triaged | Definition of Ready met | — |
| 2 | **Ready** | DoR met; estimated; unblocked | A developer pulls it | 20 |
| 3 | **In Progress** | Branch created; developer assigned | Implementation complete, tests pass locally | **1 per developer** |
| 4 | **Code Review** | PR open against `develop` | Approved by CODEOWNER; all checks green | 5 |
| 5 | **Testing** | Merged to `develop`; deployed to Development | All ACs verified by QA | 8 |
| 6 | **Ready for Staging** | QA verified | Included in a `release/*` branch | — |
| 7 | **Done** | Deployed to staging and accepted by the Product Owner | — | — |

> **The In Progress limit of 1 per developer is the project rule** — *never implement more than one
> user story at a time* — expressed as a board constraint rather than a promise. If someone needs a
> second card in that column, the first one should have been split or marked blocked.

### Board views

| View | Filter | Used in |
|---|---|---|
| **Delivery** (default) | all open | Daily stand-up |
| **Current Sprint** | `milestone:<current>` | Sprint execution |
| **Blocked** | `label:status/blocked` | Dependency review |
| **By Phase** | grouped by `phase/*` | Milestone planning |
| **By Component** | grouped by `component/*` | Architecture review |
| **Unbacked** | `label:provenance/tdd-derived,provenance/unbacked` | **Sprint-review reverse trace** |
| **ACS Gated** | `label:acs-stage/B-real-contract` | TODO-02 impact tracking |
| **Security & PII** | `label:security,privacy/pii` | Security review |

The **Unbacked** view is the one that keeps the project honest. Anything in it that has moved past
`Ready` needs an explanation.

### Custom fields

| Field | Type | Values |
|---|---|---|
| Story Points | number | 1, 2, 3, 5, 8 |
| Phase | single select | Phase 1 … Phase 4 |
| Epic | text | `EPIC-07` |
| Requirement | text | `FR-VMS-01 (SRS B1)` |
| ACS Stage | single select | A (simulator) · B (real contract) · N/A |
| Sprint | iteration | 2-week iterations |
| Blocked By | text | `TODO-02` |

---

## 3. Board automation

| Trigger | Action |
|---|---|
| Issue created | → **Backlog**, add `status/backlog` |
| `status/ready` applied | → **Ready** |
| Issue assigned + branch created | → **In Progress**, swap to `status/in-progress` |
| PR opened linking the issue | → **Code Review**, swap to `status/code-review` |
| PR merged | → **Testing**, swap to `status/testing` |
| QA verification label applied | → **Ready for Staging** |
| Included in a `release/*` branch | → stays in **Ready for Staging** |
| Release deployed to staging + PO accepts | → **Done**, swap to `status/done`, close |
| `status/blocked` applied | stays in column, flagged in the **Blocked** view |

Status labels are mirrored so that filtering works in issue search (which cannot query board
columns), and so the board can be rebuilt from labels if it is ever lost.

---

## 4. Required labels per issue type

| Type | Type | Priority | Status | Component | Provenance | Phase |
|---|---|---|---|---|---|---|
| Epic | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Feature | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| User Story | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Task | ✅ | ✅ | ✅ | ✅ | inherited | inherited |
| Bug | ✅ | ✅ | ✅ | ✅ | n/a | ✅ |
| Spike | ✅ | ✅ | ✅ | optional | n/a | optional |

## 5. Applying the labels

```bash
gh auth login
./scripts/bootstrap-github.sh --dry-run    # preview
./scripts/bootstrap-github.sh              # apply
```

Re-running is safe — existing labels are updated in place (`gh label create --force`), milestones and
epics are skipped if they already exist.
