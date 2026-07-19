#!/usr/bin/env bash
#
# bootstrap-github.sh — create the GitHub project structure for Project Pinnacle VMS.
#
# Creates, idempotently:
#   1. Labels           (from .github/labels.yml)
#   2. Milestones       (M0 .. M6, per docs/project/04-milestones-and-release-plan.md)
#   3. Epic issues      (EPIC-01 .. EPIC-14, per docs/project/03-epics-and-backlog.md)
#
# Requires: gh (https://cli.github.com), authenticated with repo scope.
#
# Usage:
#   ./scripts/bootstrap-github.sh [--dry-run] [--repo owner/name] [--skip-labels|--skip-milestones|--skip-epics]
#
# Re-running is safe: existing labels are updated, existing milestones and epics are skipped.

set -euo pipefail

DRY_RUN=0
REPO=""
DO_LABELS=1; DO_MILESTONES=1; DO_EPICS=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)         DRY_RUN=1 ;;
    --repo)            REPO="$2"; shift ;;
    --skip-labels)     DO_LABELS=0 ;;
    --skip-milestones) DO_MILESTONES=0 ;;
    --skip-epics)      DO_EPICS=0 ;;
    -h|--help)         sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ---------------------------------------------------------------- helpers
c_ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
c_skip() { printf '\033[90m·\033[0m %s\n' "$*"; }
c_info() { printf '\033[36m▸\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m!\033[0m %s\n' "$*"; }
c_err()  { printf '\033[31m✗\033[0m %s\n' "$*" >&2; }

run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '\033[90m  would run:\033[0m %s\n' "$*"
    return 0
  fi
  "$@"
}

gh_repo_args() { [[ -n "$REPO" ]] && printf -- '--repo\n%s\n' "$REPO"; }

# ---------------------------------------------------------------- preflight
if ! command -v gh >/dev/null 2>&1; then
  c_err "gh CLI not found. Install from https://cli.github.com and run 'gh auth login'."
  exit 1
fi

if ! gh auth status >/dev/null 2>&1; then
  c_err "gh is not authenticated. Run: gh auth login"
  exit 1
fi

if [[ -z "$REPO" ]]; then
  if ! REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)"; then
    c_err "No GitHub remote found and --repo not given."
    c_err "Create the repository first, or pass --repo owner/name."
    exit 1
  fi
fi

c_info "Repository: $REPO"
[[ $DRY_RUN -eq 1 ]] && c_warn "DRY RUN — nothing will be created."
echo

# ---------------------------------------------------------------- 1. labels
if [[ $DO_LABELS -eq 1 ]]; then
  c_info "Labels"
  LABELS_FILE=".github/labels.yml"
  [[ -f "$LABELS_FILE" ]] || { c_err "$LABELS_FILE not found"; exit 1; }

  name=""; color=""; desc=""
  flush_label() {
    [[ -z "$name" ]] && return 0
    if run gh label create "$name" --color "$color" --description "$desc" --force --repo "$REPO" 2>/dev/null; then
      c_ok "$name"
    else
      c_warn "$name (failed — check permissions)"
    fi
    name=""; color=""; desc=""
  }

  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      "- name: "*)     flush_label; name="$(sed 's/^- name: *"\(.*\)"$/\1/' <<<"$line")" ;;
      "  color: "*)    color="$(sed 's/^  color: *"\(.*\)"$/\1/' <<<"$line")" ;;
      "  description: "*) desc="$(sed 's/^  description: *"\(.*\)"$/\1/' <<<"$line")" ;;
    esac
  done < "$LABELS_FILE"
  flush_label
  echo
fi

# ---------------------------------------------------------------- 2. milestones
if [[ $DO_MILESTONES -eq 1 ]]; then
  c_info "Milestones"

  existing_ms="$(gh api "repos/$REPO/milestones?state=all&per_page=100" -q '.[].title' 2>/dev/null || true)"

  create_milestone() {
    local title="$1" description="$2"
    if grep -Fxq "$title" <<<"$existing_ms"; then
      c_skip "$title (exists)"
      return 0
    fi
    if run gh api "repos/$REPO/milestones" -f title="$title" -f description="$description" -f state=open >/dev/null; then
      c_ok "$title"
    else
      c_warn "$title (failed)"
    fi
  }

  create_milestone "M0 — Inception & Requirements Baseline" \
    "Requirements analysis, traceability baseline B1, GitHub project scaffolding, dependency register. No application code. See docs/project/04-milestones-and-release-plan.md"
  create_milestone "M1 — Foundation, Identity & Master Data" \
    "EPIC-01, EPIC-02, EPIC-03 (blocked), EPIC-14 (partial). Delivers FR-ADM-01, FR-ADM-02. Release 0.1.0"
  create_milestone "M2 — Visitor Request & Approval Workflow" \
    "EPIC-04, EPIC-13. Delivers FR-VMS-01/02/03, FR-ADM-03. First client-demonstrable release, zero ACS dependency. Release 0.2.0"
  create_milestone "M3 — ACS Integration & Credential Lifecycle" \
    "GATED on TODO-02. EPIC-12, EPIC-05, EPIC-07. Delivers FR-VMS-04..07, FR-VMS-11..14, FR-API-01/02/03. Stage A buildable now against simulator; Stage B needs the UAL contract. Releases 0.3.0 / 0.4.0"
  create_milestone "M4 — Reception Operations" \
    "GATED on M3 Stage B. EPIC-06, EPIC-08, EPIC-09. Delivers FR-VMS-08/09/10/15, FR-CRD-01/02/03. Release 0.5.0"
  create_milestone "M5 — Notifications & Reporting" \
    "EPIC-10, EPIC-11. Delivers FR-NOT-01/02, FR-REP-01/02. Email path can pull forward into M2. Release 0.6.0"
  create_milestone "M6 — Hardening & Release 1.0" \
    "NFR verification, OWASP ASVS review, penetration test, DPIA, full traceability sign-off, runbook. Release 1.0.0"
  echo
fi

# ---------------------------------------------------------------- 3. epics
if [[ $DO_EPICS -eq 1 ]]; then
  c_info "Epic issues"

  existing_titles="$(gh issue list --repo "$REPO" --state all --limit 500 --json title -q '.[].title' 2>/dev/null || true)"

  create_epic() {
    local title="$1" milestone="$2" labels="$3" body="$4"
    if grep -Fxq "$title" <<<"$existing_titles"; then
      c_skip "$title (exists)"
      return 0
    fi
    if run gh issue create --repo "$REPO" --title "$title" --body "$body" \
         --milestone "$milestone" --label "$labels" >/dev/null; then
      c_ok "$title"
    else
      c_warn "$title (failed — ensure labels and milestones exist first)"
    fi
  }

  epic_body() {
    # $1 requirements  $2 goal  $3 status  $4 blockers
    cat <<EOF
## SRS requirement IDs

$1

## Goal

$2

## Status

$3

## Open questions / blockers

$4

---

**Traceability:** Requirement → Milestone → **Epic** → Feature → User Story → Task → Code → Test → PR → Release

- Requirements catalogue: \`docs/project/01-requirements-catalogue.md\`
- Feature & story decomposition: \`docs/project/03-epics-and-backlog.md\`
- Traceability matrix: \`docs/project/02-traceability-matrix.md\`
- Open questions: \`docs/project/07-open-questions.md\`

> Project rule 2: never implement functionality that is not in the SRS.
EOF
  }

  M1="M1 — Foundation, Identity & Master Data"
  M2="M2 — Visitor Request & Approval Workflow"
  M3="M3 — ACS Integration & Credential Lifecycle"
  M4="M4 — Reception Operations"
  M5="M5 — Notifications & Reporting"

  create_epic "[EPIC-01] Platform Foundation & Delivery Pipeline" "$M1" \
    "type/epic,type/enabler,area/platform,status/ready,priority/critical" \
    "$(epic_body "**NONE — enabler epic.**" \
       "Build, test and deployment substrate. Ships no business logic. Justified by NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 and TDD §3/§9." \
       "Ready — 6 user stories written." \
       "TODO-12 (data retention period) blocks F-01.7 only.")"

  create_epic "[EPIC-02] Identity, Authentication & RBAC" "$M1" \
    "type/epic,area/identity,security,status/ready,priority/critical" \
    "$(epic_body "FR-ADM-01 (SRS B1), FR-ADM-02 (SRS B1). Supports NFR-SEC-01." \
       "Authentication, session lifecycle, role/permission model enforced at the API boundary, and the Master Admin approval authority." \
       "Ready — 5 user stories written." \
       "TODO-08 (what \"120 logins\" means) blocks F-02.4. TODO-14 (tenant isolation) blocks F-02.5. TODO-15 (local accounts vs SSO).")"

  create_epic "[EPIC-03] Configuration & Master Data" "$M1" \
    "type/epic,area/master-data,status/blocked,blocked/srs-v2,unbacked-by-srs,priority/critical" \
    "$(epic_body "**NONE in the attached SRS.** TDD §4.1 and the schema cite FR-CFG-01..08, which are undefined. See D-03, D-05." \
       "Master data: Building, Floor, Tenant, Reception, Visitor Type, Pass Type, Holiday Calendar, System Settings." \
       "🔴 BLOCKED. Building this means inventing requirements, which project rule 2 forbids. No user stories will be written until TODO-01 resolves." \
       "TODO-01 — supply SRS v2, or confirm the attached SRS is the delivery baseline.")"

  create_epic "[EPIC-04] Visitor Request & Approval Workflow" "$M2" \
    "type/epic,area/visitor,status/ready,priority/high" \
    "$(epic_body "FR-VMS-01 (SRS B1), FR-VMS-02 (SRS B1), FR-VMS-03 (SRS B1)" \
       "The complete pre-arrival journey: tenant submits a request, FM Admin approves or rejects, floor receptionist pre-registers to the central server." \
       "Ready — 8 user stories written. **This is the largest unblocked slice of the SRS and the first client-demonstrable milestone.**" \
       "Depends on EPIC-02, and on EPIC-03 for Tenant/Host references — see the M1 risk note in the milestone plan.")"

  create_epic "[EPIC-05] Arrival Verification & Credential Issuance" "$M3" \
    "type/epic,area/credential,status/blocked,blocked/acs-contract,priority/high" \
    "$(epic_body "FR-VMS-04 (SRS B1), FR-VMS-05 (SRS B1), FR-VMS-06 (SRS B1), FR-VMS-07 (SRS B1)" \
       "Arrival verification against pre-registration, credential creation request to ACS, storing the returned credential reference, and sharing it by email, screen or print." \
       "🔴 BLOCKED on the ACS contract. F-05.1 (arrival verification) uses only VMS-owned data and can start once EPIC-04 lands." \
       "TODO-02 — ACS API contract.")"

  create_epic "[EPIC-06] Walk-in & Express Entry" "$M4" \
    "type/epic,area/entry,status/blocked,blocked/acs-contract,status/descope-candidate,priority/medium" \
    "$(epic_body "FR-VMS-08 (SRS B1), FR-VMS-09 (SRS B1), FR-VMS-10 (SRS B1)" \
       "Walk-in registration and approval at reception, credential request via the same ACS path, and express entry for pre-scheduled visitors." \
       "🔴 BLOCKED. **F-06.4 (express entry, FR-VMS-10) may be descoped entirely** — the SRS itself notes the vendor describes a fully reception-mediated flow. Do not plan capacity against it." \
       "TODO-02 (ACS contract), TODO-03 (is express entry achievable), TODO-18 (walk-in approval channel).")"

  create_epic "[EPIC-07] Credential Validity & Lifecycle" "$M3" \
    "type/epic,area/credential,status/blocked,blocked/acs-contract,priority/high" \
    "$(epic_body "FR-VMS-11 (SRS B1), FR-VMS-12 (SRS B1), FR-VMS-13 (SRS B1), FR-VMS-14 (SRS B1)" \
       "Validity windows, deactivation at end of window, time-bound vs one-time-use restrictions, and on-demand status query." \
       "🔴 BLOCKED. **F-07.5 (manual override) has no SRS requirement and must not be built** — see D-06." \
       "TODO-02 (ACS contract), TODO-09 (does VMS or ACS expire the credential), TODO-04 (is manual override in scope).")"

  create_epic "[EPIC-08] Visitor-Facing Reception Display" "$M4" \
    "type/epic,area/frontend,status/ready,priority/medium" \
    "$(epic_body "FR-VMS-15 (SRS B1)" \
       "Slave display at the reception desk showing the visitor's QR code and current status." \
       "Ready to plan. Unblocked — **can pull forward into M2 if M3 slips.**" \
       "TODO-17 — physical and interaction model is undefined (dedicated device? pairing? idle state?).")"

  create_epic "[EPIC-09] Card Accountability & Reconciliation" "$M4" \
    "type/epic,area/entry,status/blocked,blocked/acs-contract,priority/medium" \
    "$(epic_body "FR-CRD-01 (SRS B1), FR-CRD-02 (SRS B1), FR-CRD-03 (SRS B1)" \
       "Log RFID card issuance and return from ACS-reported events; reconcile issued-vs-returned daily and flag missing cards." \
       "🔴 BLOCKED — depends entirely on ACS-reported card events." \
       "TODO-02 (ACS contract), TODO-11 (reconciliation run time, timezone, day cut-off, who receives the discrepancy flag).")"

  create_epic "[EPIC-10] Notifications & Alerts" "$M5" \
    "type/epic,area/notification,status/ready,priority/medium" \
    "$(epic_body "FR-NOT-01 (SRS B1), FR-NOT-02 (SRS B1)" \
       "Event-driven notification dispatch with delivery status logging: credential confirmation to host and visitor, and exit alerts." \
       "🟠 PARTIAL — 4 user stories written for the email path. **The email path can pull forward into M2**, converting M3 delay into shipped coverage." \
       "TODO-05 (WhatsApp Business API approval — build behind a disabled flag), TODO-10 (who chooses the channel), TODO-02 (exit events come from ACS).")"

  create_epic "[EPIC-11] Reporting & Analytics" "$M5" \
    "type/epic,area/reporting,status/ready,priority/medium" \
    "$(epic_body "FR-REP-01 (SRS B1), FR-REP-02 (SRS B1)" \
       "Automatic daily/weekly/monthly visitor activity reports, and the end-of-day credentials-and-cards issued/returned report." \
       "🟠 PARTIAL — report structure can be built; the ACS-sourced half of the data is gated." \
       "TODO-02 (ACS access/exit events), TODO-16 (are Excel/PDF exports in scope — TDD-only).")"

  create_epic "[EPIC-12] ACS Integration Client & Reliability" "$M3" \
    "type/epic,area/acs-integration,status/ready,security,nfr,priority/critical" \
    "$(epic_body "FR-API-01 (SRS B1), FR-API-02 (SRS B1), FR-API-03 (SRS B1). Supports NFR-REL-01, NFR-MNT-01, CON-01, CON-02." \
       "The single choke-point for all ACS calls: the port, the anti-corruption adapter, the simulator, retry and dead-letter handling, request/response audit logging, service authentication, idempotent inbound events, and the periodic sync job." \
       "🟠 PARTIAL — **5 user stories ready and this is the highest-value work available right now.** Only F-12.9 (the wire-level adapter) is blocked. See ADR-0002." \
       "TODO-02 blocks F-12.9 only. Everything else — including the simulator that unblocks testing for EPIC-05/07/09 — can start immediately.")"

  create_epic "[EPIC-13] Portal Branding & Design System" "$M2" \
    "type/epic,area/frontend,status/ready,priority/medium" \
    "$(epic_body "FR-ADM-03 (SRS B1), CON-03 (SRS B1)" \
       "Design tokens, shared component library across tenant/admin/reception/display views, and an accessibility baseline." \
       "Ready. Not blocking — build against a neutral token set and swap when brand assets arrive." \
       "Client brand assets and guidelines have not been supplied. F-13.3 (WCAG 2.1 AA) is derived from NFR-USA-01, not stated — confirm.")"

  create_epic "[EPIC-14] Audit, Security & Observability" "$M1" \
    "type/epic,type/enabler,area/audit,security,nfr,status/ready,priority/high" \
    "$(epic_body "NFR-SEC-01 (SRS B1) directly. FR-AUD-01/02 are referenced by the TDD and schema but undefined — see D-03." \
       "Structured logging with correlation ids, append-only audit log, encryption in transit and at rest, secrets management, OWASP ASVS baseline in CI, health checks and alerting." \
       "🟠 PARTIAL — everything except the audit log itself can proceed." \
       "TODO-01 blocks F-14.2 (append-only audit log — TDD/schema-only, no SRS requirement).")"
  echo
fi

# ---------------------------------------------------------------- next steps
c_info "Done."
cat <<'EOF'

Next steps (manual — these need repository admin):

  1. Replace placeholder team handles in .github/CODEOWNERS with real GitHub teams.
  2. Update the URLs in .github/ISSUE_TEMPLATE/config.yml to the real repository.
  3. Create the `develop` branch:      git checkout -b develop && git push -u origin develop
  4. Set `develop` as the default branch in repository settings.
  5. Enable branch protection on `main` and `develop` — see
     docs/project/05-workflow-and-branching.md#branch-protection-configure-before-first-merge
  6. Create a GitHub Project (board) with columns:
     Backlog · Ready · In Progress · In Review · Blocked · Done
  7. Raise Spike issues for the 4 blocking open questions:
     TODO-01 (SRS v2), TODO-02 (ACS API contract), TODO-03 (express entry), TODO-19 (gate authority)
  8. Decompose EPIC-01, EPIC-02, EPIC-04, EPIC-12 into feature and user story issues
     from docs/project/03-epics-and-backlog.md — 33 stories are already written.

EOF
