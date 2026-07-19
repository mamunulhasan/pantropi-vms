# Branch Strategy

**Model:** git-flow. Feature branches only. Nothing is ever committed directly to `develop` or `main`.

This is the canonical branch reference. [05-workflow-and-branching.md](05-workflow-and-branching.md)
covers the surrounding development workflow (commits, DoR/DoD, architecture rules).

---

## Branch topology

```
main ────●───────────────────────●───────────────────●──────────►  production, tagged releases
          \                     /                   /
           \                   / release/0.2.0     / hotfix/0.2.1
            \                 /                   /
develop ─────●───●───●───●───●───●───●───●───●───●────────────────►  integration, always deployable
              \     /     \     /     \     /
               \   /       \   /       \   /
                ●─●         ●─●         ●─●
        feature/US-07.1.1  feature/US-07.3.1  bugfix/142-…
```

## Branch types

| Branch | Purpose | Branches from | Merges to | Lifetime | Protected |
|---|---|---|---|---|---|
| `main` | Production. Every commit is a tagged release. | — | — | permanent | ✅ |
| `develop` | Integration. Always deployable to staging. | `main` | — | permanent | ✅ |
| `feature/*` | **One user story.** | `develop` | `develop` | ≤ 1 sprint | — |
| `bugfix/*` | One defect found on `develop`. | `develop` | `develop` | short | — |
| `release/*` | Release stabilisation — no new features. | `develop` | `main` **and** `develop` | days | — |
| `hotfix/*` | Urgent production defect. | `main` | `main` **and** `develop` | hours | — |
| `spike/*` | Time-boxed investigation. **Never merged.** | `develop` | *(discarded)* | ≤ time box | — |
| `docs/*` | Documentation-only change. | `develop` | `develop` | short | — |

## Naming

```
feature/US-07.1.1-tenant-submit-visitor-request
feature/US-11.3.1-acs-outbound-retry-queue
bugfix/142-approval-dashboard-pagination
docs/update-traceability-matrix-phase-2
spike/TODO-02-acs-contract-investigation
release/0.2.0
hotfix/0.2.1-credential-expiry-timezone
```

**Rules**
- A feature branch carries its **user story id**. This is what makes `git log` traceable to a
  requirement without opening GitHub.
- Bugfix branches carry the **issue number**.
- Spike branches carry the **TODO id**.
- Lowercase, hyphen-separated, no spaces. Keep under ~60 characters.

## One story, one branch

Project rule: **never implement more than one user story at a time.** One story → one branch → one
PR → one squashed commit on `develop`.

If a story turns out too large mid-flight, do not widen the branch. Split the story, close the
current branch at a coherent point, and open a new one. A branch that grows to cover two stories
cannot be reviewed against a single requirement, and it breaks the traceability claim that the diff
implements exactly one thing.

---

## Lifecycle

### Feature

```bash
git checkout develop && git pull
git checkout -b feature/US-07.1.1-tenant-submit-visitor-request
# … work, committing in Conventional Commits format …
git push -u origin feature/US-07.1.1-tenant-submit-visitor-request
# open PR → develop, using the PR template
```

Merged with **squash**, keeping the Conventional Commit subject. Branch deleted on merge.

### Release

```bash
git checkout -b release/0.2.0 develop
# version bump, changelog, release-note generation, stabilisation fixes only
# NO new features on a release branch
git checkout main && git merge --no-ff release/0.2.0
git tag -a v0.2.0 -m "Phase 2 — Visitor Registration & Pass Generation"
git checkout develop && git merge --no-ff release/0.2.0   # merge back
```

Merged with **merge commit** (`--no-ff`), preserving release history on `main`.

### Hotfix

```bash
git checkout -b hotfix/0.2.1-credential-expiry-timezone main
# minimal fix + regression test
git checkout main && git merge --no-ff hotfix/0.2.1-credential-expiry-timezone
git tag -a v0.2.1
git checkout develop && git merge --no-ff hotfix/0.2.1-credential-expiry-timezone
```

**Always merge back to `develop`**, or the fix is lost in the next release.

---

## Branch protection

Configure before the first feature merge.

### `main`
| Setting | Value |
|---|---|
| Require pull request | ✅ |
| Required approvals | **2** |
| Require CODEOWNERS review | ✅ |
| Dismiss stale approvals on new commits | ✅ |
| Require conversation resolution | ✅ |
| Require branches up to date | ✅ |
| Require linear history | ✅ |
| Allow force push / deletion | ❌ |
| Include administrators | ✅ |
| Required status checks | all (below) |

### `develop`
| Setting | Value |
|---|---|
| Require pull request | ✅ |
| Required approvals | **1** (2 for `area/acs-integration` or `security`) |
| Require CODEOWNERS review | ✅ |
| Dismiss stale approvals | ✅ |
| Require conversation resolution | ✅ |
| Require branches up to date | ✅ |
| Allow force push / deletion | ❌ |
| Include administrators | ✅ |
| Required status checks | all (below) |

### Required status checks

| Check | Gate |
|---|---|
| `build` | Compiles |
| `unit-tests` | All pass |
| `integration-tests` | All pass (Testcontainers PostgreSQL) |
| `coverage` | No regression against baseline |
| `architecture-fitness` | **Layer rules + the ACS-boundary rule** |
| `commit-lint` | Conventional Commits |
| `sast` | No new high/critical findings |
| `dependency-scan` | No new high/critical CVEs |
| `openapi-lint` | API contract valid (when changed) |

> ⚠️ **CODEOWNERS currently references org team handles** (`@pantropi/vms-architects` etc.). The
> repository is personal (`mamunulhasan/pantropi-vms`), where teams do not exist — so every review
> rule silently fails to apply. Either move the repo under a `pantropi` organisation, or replace the
> handles with individual usernames, **before** enabling protection.

---

## Environment mapping

| Branch | Environment | Deployment | ACS endpoint |
|---|---|---|---|
| `feature/*` | ephemeral / local | on demand | ACS **simulator** |
| `develop` | Development | automatic on merge | ACS simulator |
| `release/*` | Staging | automatic on branch creation | ACS **test endpoint** (TDD §9.3) |
| `main` | Production | manual approval gate | ACS production |

Per TDD §9.3, non-production always points at an ACS test endpoint — never production ACS. Until
TODO-02 resolves and UAL provides that endpoint, **every environment below staging uses the
simulator**, and nothing ACS-dependent can be marked verified.

---

## Merge strategy

| Merge | Strategy | Why |
|---|---|---|
| `feature/*` → `develop` | **Squash** | One story = one commit. Clean, traceable history. |
| `bugfix/*` → `develop` | **Squash** | Same. |
| `release/*` → `main` | **Merge commit** (`--no-ff`) | Preserves release boundaries. |
| `release/*` → `develop` | **Merge commit** | Carries stabilisation fixes back. |
| `hotfix/*` → both | **Merge commit** | Preserves the hotfix as a distinct event. |

## Versioning & tags

[Semantic Versioning](https://semver.org). Tags are `v<major>.<minor>.<patch>` on `main` only.

| Change | Bump |
|---|---|
| Breaking API change | major |
| New feature, backward compatible | minor |
| Bug fix, backward compatible | patch |

Pre-1.0, minor bumps carry each phase. See [12-release-plan.md](12-release-plan.md).

## Stale branch policy

| Age | Action |
|---|---|
| 7 days without a commit | Author pinged in stand-up |
| 14 days | Escalated — likely a story that should have been split |
| 30 days | Closed; work re-planned |

A long-lived feature branch means the story was too big, was blocked and should have been marked
`status/blocked`, or is being worked alongside another story. All three are process failures worth
catching early.
