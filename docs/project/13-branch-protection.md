# Branch Protection — Configuration, Deviations & Blockers

**Story:** US-01.1.1 · **Task:** T-01.1.1.3 · **Status:** ✅ Applied and verified

Protection is configured **as code** in [`.github/branch-protection/`](../../.github/branch-protection/),
applied by [`apply-branch-protection.sh`](../../scripts/apply-branch-protection.sh), and asserted by
[`verify-branch-protection.sh`](../../scripts/verify-branch-protection.sh).

The committed JSON is the source of truth. Protection changed through the GitHub UI is drift, and
the verify script exists to catch it.

---

## Resolution — repository made public

Branch protection is **applied and verified** on `main` and `develop`.

### How this was unblocked

The repository was private on a free personal plan, where GitHub does not offer branch protection
(`HTTP 403: Upgrade to GitHub Pro or make this repository public`).

**The repository owner elected to make the repository public**, on 2026-07-19, after being advised
against it. Recorded here for the project record.

### Risk accepted by that decision

Advice given at the time, and the exposure it identified:

| Exposed | Detail |
|---|---|
| Client identity & commercial relationship | CPG Corporation Pte Ltd, Westgate Tower |
| Vendor identity & an unfinished dependency | Universal Automations Ltd; their API contract is undelivered (TODO-02) |
| Third-party source documents **in git history** | `VMS_Only_SRS_Project_Pinnacle.docx`, `VMS Technical Design Document.docx` — the latter references UAL financial proposal R3 |
| Physical access-control design | Data model, role and permission set, reception/floor topology of an occupied building |
| Known security defects | All 15 discrepancies, including **D-15** — an unseeded `role_permissions` table producing a total lockout |

**These documents are in git history, not merely the working tree.** Removing them later requires
rewriting history, and does not undo any clone, fork, or cache made while the repository was public.

**Recommended remediation** if confidentiality is later required: move the client `.docx` files and
the discrepancy register to a private repository, keep the code public, and rewrite history to purge
the documents. This is materially harder than not publishing them, which is why the advice was to
choose GitHub Pro (~US$4/month) or an organisation instead.

**Owner acknowledgement:** the decision was taken explicitly by the repository owner with the above
stated. It was not a default, and it was not taken by the implementer.

---

## Configuration as applied

| Setting | `main` | `develop` | Acceptance criterion |
|---|---|---|---|
| Require pull request | ✅ | ✅ | AC-2 |
| Required approvals | **0** ⚠️ | **0** ⚠️ | AC-2 — *deviation* |
| Require CODEOWNER review | **false** ⚠️ | **false** ⚠️ | AC-3 — *deviation* |
| Dismiss stale approvals | ✅ | ✅ | AC-5 |
| Require conversation resolution | ✅ | ✅ | AC-2 |
| Require branch up to date | ✅ | ✅ | AC-2 |
| Required status checks | `commit-lint` | `commit-lint` | AC-2 — partially; rest with US-01.4.x |
| Include administrators | ✅ | ✅ | AC-4 |
| Require linear history | ❌ *(deliberate)* | ✅ | — |
| Force pushes | blocked | blocked | — |
| Deletions | blocked | blocked | — |

---

## Deviation register

### DEV-01 — Required approvals set to 0

**Against:** AC-2 (≥1 CODEOWNER approval), AC-3 (2 approvals on ACS/security paths)

GitHub never requests review from the author of a pull request. This repository has one
collaborator, so **any non-zero approval requirement would block every merge permanently** — the
only person who could approve is the person who opened the PR.

**Resolution:** raise to 1 (2 for ACS and security paths) and enable code-owner review the moment a
second collaborator or an organisation exists. Option 2 above resolves this and AC-3 together.

**Risk accepted meanwhile:** no enforced second pair of eyes. Review discipline is procedural, not
technical.

### DEV-02 — Required status checks incomplete *(shrinking)*

**Against:** AC-2 (all required status checks pass)

**`commit-lint` is now registered and required** on both branches (US-01.1.2): Conventional
Commits, the story-id rule, and the D-02 qualified-reference rule are mechanically enforced on
every PR. Local hook available via `scripts/install-git-hooks.sh`.

**Remaining:** the build/test pipeline lands with US-01.4.x; add to both configs and re-apply:

```
build · unit-tests · integration-tests · coverage · architecture-fitness
sast · dependency-scan
```

The **architecture-fitness** check is the important one — it enforces the ACS boundary rule
(CON-01, CON-02, NFR-MNT-01) that the entire TODO-02 mitigation depends on.

**Risk accepted meanwhile:** merges are not gated on tests passing.

### DEV-03 — `required_linear_history` is false on `main`

**Not a deviation from the story — a correction to our own branch strategy.**

[10-branch-strategy.md](10-branch-strategy.md) listed *"Require linear history ✅"* for `main` while
also mandating that `release/*` and `hotfix/*` merge into `main` with `--no-ff`. A `--no-ff` merge
creates a merge commit, which linear history forbids. **Both cannot be true**; every release merge
would have failed.

`main` therefore allows merge commits, preserving release boundaries in history as the strategy
intends. `develop` keeps linear history, since feature branches squash-merge.

A unit test asserts this so the contradiction cannot silently return.

---

## Runbook

```bash
# Validate config offline — no network, no auth (unit tests)
./scripts/test-branch-protection-config.sh

# Preview what would be applied
./scripts/apply-branch-protection.sh --dry-run

# Apply
./scripts/apply-branch-protection.sh

# Assert live state matches committed config (integration test)
./scripts/verify-branch-protection.sh
```

Run the verify script after any GitHub settings change, and in CI once a pipeline exists. It is what
turns "we configured protection" from a claim into a tested fact.
