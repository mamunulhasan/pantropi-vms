# Branch Protection — Configuration, Deviations & Blockers

**Story:** US-01.1.1 · **Task:** T-01.1.1.3 · **Status:** ⚠️ Partially delivered — see blocker below

Protection is configured **as code** in [`.github/branch-protection/`](../../.github/branch-protection/),
applied by [`apply-branch-protection.sh`](../../scripts/apply-branch-protection.sh), and asserted by
[`verify-branch-protection.sh`](../../scripts/verify-branch-protection.sh).

The committed JSON is the source of truth. Protection changed through the GitHub UI is drift, and
the verify script exists to catch it.

---

## 🔴 Blocker — protection cannot be applied on the current plan

```
HTTP 403: Upgrade to GitHub Pro or make this repository public to enable this feature.
```

`mamunulhasan/pantropi-vms` is a **private repository on a free personal plan**. GitHub does not
offer branch protection there.

**Consequence:** AC-2, AC-4 and AC-5 of US-01.1.1 cannot be satisfied today. Nothing prevents a
direct push to `develop` or `main` except discipline.

### Options

| | Option | Cost | Assessment |
|---|---|---|---|
| **1** | **GitHub Pro** | ~US$4/month | ✅ **Recommended.** Unblocks everything immediately. The config and scripts are already written — this becomes a one-command apply. |
| **2** | Move to a GitHub **organisation** | Free tier available; Team plan ~US$4/user/month for private repos | ✅ Also solves CODEOWNERS teams (AC-3) and the approval problem (AC-2). Best long-term — the project documentation assumes an org throughout. |
| **3** | Make the repository **public** | Free | ❌ **Not recommended — see below.** |
| **4** | Defer protection | Free | ⚠️ Current state. Config committed and tested offline; applied later. |

### Why option 3 is not recommended

Making this repository public would expose:

- Client identity and commercial relationship — CPG Corporation, Westgate Tower
- Vendor identity and an unfinished commercial dependency — Universal Automations Ltd, and the fact
  that their API contract is undelivered
- Referenced commercial documents — UAL financial proposal R3, technical information sheet R2
- The full requirements baseline and 15 catalogued defects in the client's own documents
- A building access-control system's data model, roles, permission set and known security gaps —
  including **D-15**, a total-lockout defect, and the reception/floor topology of a real building

The last point is the serious one. This describes physical access control for an occupied building.
Publishing its design, permission model and known weaknesses is a security decision, not a
budget one.

**This is a client confidentiality and physical-security decision. It is not ours to take, and we
advise against it.**

---

## Configuration as applied *(pending the blocker)*

| Setting | `main` | `develop` | Acceptance criterion |
|---|---|---|---|
| Require pull request | ✅ | ✅ | AC-2 |
| Required approvals | **0** ⚠️ | **0** ⚠️ | AC-2 — *deviation* |
| Require CODEOWNER review | **false** ⚠️ | **false** ⚠️ | AC-3 — *deviation* |
| Dismiss stale approvals | ✅ | ✅ | AC-5 |
| Require conversation resolution | ✅ | ✅ | AC-2 |
| Require branch up to date | ✅ | ✅ | AC-2 |
| Required status checks | *(none yet)* | *(none yet)* | AC-2 — deferred to US-01.4.x |
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

### DEV-02 — No required status checks

**Against:** AC-2 (all required status checks pass)

`contexts` is deliberately empty. No CI pipeline exists until US-01.4.x. Requiring a check that
never reports would block every merge permanently.

**Resolution:** when the pipeline lands, add to both configs and re-apply:

```
build · unit-tests · integration-tests · coverage · architecture-fitness
commit-lint · sast · dependency-scan
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
