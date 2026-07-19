#!/usr/bin/env bash
#
# bootstrap-github.sh — create the GitHub project structure for Project Pinnacle VMS.
#
# Creates, idempotently:
#   1. Labels      (from .github/labels.yml — Type, Priority, Status, Component, Provenance, Blocker)
#   2. Milestones  (Phase 1 .. Phase 4, per docs/project/12-release-plan.md)
#   3. Epic issues (EPIC-01 .. EPIC-19, per docs/project/backlog/00-epic-feature-index.md)
#
# Features, user stories and tasks are NOT created here — there are ~93 features, 178 stories and
# 524 tasks. Create them per-phase at sprint planning from the backlog files, so estimates stay
# fresh and blocked work is not prematurely opened. See scripts/README.md.
#
# Requires: gh (https://cli.github.com), authenticated with repo scope.
#
# Usage:
#   ./scripts/bootstrap-github.sh [--dry-run] [--repo owner/name]
#                                 [--skip-labels] [--skip-milestones] [--skip-epics]
#
# Re-running is safe: labels are updated in place, milestones and epics are skipped if present.

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
    -h|--help)         sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

c_ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
c_skip() { printf '\033[90m·\033[0m %s\n' "$*"; }
c_info() { printf '\033[36m▸\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m!\033[0m %s\n' "$*"; }
c_err()  { printf '\033[31m✗\033[0m %s\n' "$*" >&2; }

run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '\033[90m  would run:\033[0m %s\n' "${*:1:6} …"
    return 0
  fi
  "$@"
}

# ---------------------------------------------------------------- preflight
command -v gh >/dev/null 2>&1 || {
  c_err "gh CLI not found. Install from https://cli.github.com then run 'gh auth login'."; exit 1; }
gh auth status >/dev/null 2>&1 || {
  c_err "gh is not authenticated. Run: gh auth login"; exit 1; }

if [[ -z "$REPO" ]]; then
  REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner 2>/dev/null)" || {
    c_err "No GitHub remote found and --repo not given."; exit 1; }
fi

c_info "Repository: $REPO"
[[ $DRY_RUN -eq 1 ]] && c_warn "DRY RUN — nothing will be created."
echo

# ================================================================= 1. LABELS
if [[ $DO_LABELS -eq 1 ]]; then
  c_info "Labels (Type · Priority · Status · Component · Provenance · Blocker)"
  LABELS_FILE=".github/labels.yml"
  [[ -f "$LABELS_FILE" ]] || { c_err "$LABELS_FILE not found"; exit 1; }

  name=""; color=""; desc=""; n_labels=0
  flush_label() {
    [[ -z "$name" ]] && return 0
    if run gh label create "$name" --color "$color" --description "$desc" --force --repo "$REPO" 2>/dev/null; then
      c_ok "$name"; n_labels=$((n_labels+1))
    else
      c_warn "$name (failed — check permissions)"
    fi
    name=""; color=""; desc=""
  }

  while IFS= read -r line || [[ -n "$line" ]]; do
    case "$line" in
      "- name: "*)        flush_label; name="$(sed 's/^- name: *"\(.*\)"$/\1/' <<<"$line")" ;;
      "  color: "*)       color="$(sed 's/^  color: *"\(.*\)"$/\1/' <<<"$line")" ;;
      "  description: "*) desc="$(sed 's/^  description: *"\(.*\)"$/\1/' <<<"$line")" ;;
    esac
  done < "$LABELS_FILE"
  flush_label
  echo "  → $n_labels labels"; echo
fi

# ============================================================= 2. MILESTONES
if [[ $DO_MILESTONES -eq 1 ]]; then
  c_info "Milestones"
  existing_ms="$(gh api "repos/$REPO/milestones?state=all&per_page=100" -q '.[].title' 2>/dev/null || true)"

  create_milestone() {
    local title="$1" description="$2"
    if grep -Fxq "$title" <<<"$existing_ms"; then c_skip "$title (exists)"; return 0; fi
    if run gh api "repos/$REPO/milestones" -f title="$title" -f description="$description" -f state=open >/dev/null; then
      c_ok "$title"
    else
      c_warn "$title (failed)"
    fi
  }

  create_milestone "Phase 1 — Platform Foundation" \
"Release 0.1.0. EPIC-01..06: engineering platform, identity, RBAC, master data, audit/security, portal shell. Delivers FR-ADM-01/02/03 (SRS B1). Pulls forward the ACS port (F-11.1) and simulator (F-11.2) — Phase 2 cannot build credentials without them. EPIC-04 is TDD-derived and blocked on TODO-01."

  create_milestone "Phase 2 — Visitor Registration & Pass Generation" \
"Release 0.2.0. EPIC-07..10: visitor request, approval, pre-registration, pass and credential generation, host management. Delivers FR-VMS-01/02/03/05/06/07/11/13 (SRS B1). FIRST CLIENT-DEMONSTRABLE RELEASE. Credential work runs against the ACS simulator only — done-against-simulator, not verified (ADR-0002)."

  create_milestone "Phase 3 — Entry Verification & ACS Integration" \
"Releases 0.3.0 (Stage A, simulator) and 0.4.0 (Stage B, real contract). EPIC-11..15: ACS integration client, arrival verification, walk-in and express entry, credential lifecycle, card accountability. Delivers FR-VMS-04/08/09/10/12/14/15, FR-CRD-01/02/03, FR-API-01/02/03 (SRS B1). GATED on TODO-02."

  create_milestone "Phase 4 — Reporting & Notification" \
"Release 0.5.0. EPIC-16..19: notification platform and scenarios, reporting suite, analytics and export. Delivers FR-NOT-01/02, FR-REP-01/02 (SRS B1). Largely TDD-derived — FR-NOT-03/04/05, FR-REP-06, FR-ANL-01, FR-EXP-01 have no SRS backing and need TODO-01 disposition."

  create_milestone "Release 1.0 — Hardening & Production" \
"Release 1.0.0. NFR verification (performance, load, availability), OWASP ASVS review, penetration test, data protection impact assessment, full traceability sign-off, runbook and training. All 19 open questions dispositioned."
  echo
fi

# ================================================================== 3. EPICS
if [[ $DO_EPICS -eq 1 ]]; then
  c_info "Epic issues"
  existing_titles="$(gh issue list --repo "$REPO" --state all --limit 500 --json title -q '.[].title' 2>/dev/null || true)"

  M1="Phase 1 — Platform Foundation"
  M2="Phase 2 — Visitor Registration & Pass Generation"
  M3="Phase 3 — Entry Verification & ACS Integration"
  M4="Phase 4 — Reporting & Notification"

  n_epics=0
  create_epic() {
    local title="$1" milestone="$2" labels="$3" reqs="$4" goal="$5" status="$6" blockers="$7" features="$8"
    if grep -Fxq "$title" <<<"$existing_titles"; then c_skip "$title (exists)"; return 0; fi

    local body
    body="$(cat <<EOF
## Requirements

$reqs

## Goal

$goal

## Features

$features

## Status

$status

## Open questions / blockers

$blockers

---

**Traceability:** Requirement → Milestone → **Epic** → Feature → User Story → Task → Code → Test → PR → Release

| Reference | Path |
|---|---|
| Epic & feature index | \`docs/project/backlog/00-epic-feature-index.md\` |
| Phase backlog (stories + tasks) | \`docs/project/backlog/phase-*.md\` |
| Requirements catalogue | \`docs/project/01-requirements-catalogue.md\` |
| Traceability matrix | \`docs/project/02-traceability-matrix.md\` |
| Open questions | \`docs/project/07-open-questions.md\` |

> **Project rule 2:** never implement functionality that is not in the SRS. Items marked
> \`TDD-DERIVED\` are planned but **not authorized for implementation** until TODO-01 is dispositioned.
EOF
)"
    if run gh issue create --repo "$REPO" --title "$title" --body "$body" \
         --milestone "$milestone" --label "$labels" >/dev/null; then
      c_ok "$title"; n_epics=$((n_epics+1))
    else
      c_warn "$title (failed — ensure labels and milestones exist first)"
    fi
  }

  # ---------------------------------------------------------- PHASE 1
  create_epic "[EPIC-01] Engineering Platform & Delivery Pipeline" "$M1" \
    "type/epic,type/enabler,priority/P0-critical,status/backlog,component/platform,provenance/enabler,phase/1-platform-foundation" \
    "**ENABLER** — no direct SRS requirement. Justified by NFR-MNT-01, NFR-SCL-01, NFR-AVL-01 (SRS B1); TDD §3, §9." \
    "Build, test and deployment substrate. Ships no business logic. Creates the Clean Architecture skeleton whose boundaries are enforced in CI — including the ACS-boundary rule that the entire TODO-02 mitigation depends on." \
    "Ready — 7 features." \
    "TODO-12 (data retention period) affects F-01.7 only." \
    "F-01.1 Repository & commit governance · F-01.2 Clean Architecture skeleton · F-01.3 Local dev environment · F-01.4 CI pipeline · F-01.5 Migration framework · F-01.6 Containerisation · F-01.7 Observability foundation"

  create_epic "[EPIC-02] Identity, Authentication & Session Management" "$M1" \
    "type/epic,priority/P0-critical,status/backlog,component/identity,security,provenance/srs,phase/1-platform-foundation" \
    "FR-ADM-01 (SRS B1), FR-ADM-02 (SRS B1). Supports NFR-SEC-01 (SRS B1)." \
    "Authentication, JWT session lifecycle, user provisioning at the 120+1 scale required by FR-ADM-02, account security policy, and the Master Admin approval authority required by FR-ADM-01." \
    "Ready — 4 features." \
    "TODO-08 — \"120 floor-reception logins\" is ambiguous (named accounts / shared per-floor / concurrent ceiling). Affects audit attribution. TODO-15 — local accounts vs client SSO is unstated." \
    "F-02.1 Authentication & JWT sessions · F-02.2 User provisioning at scale · F-02.3 Account security policy · F-02.4 Master Admin authority"

  create_epic "[EPIC-03] Authorization & RBAC" "$M1" \
    "type/epic,priority/P0-critical,status/backlog,component/rbac,security,provenance/srs,phase/1-platform-foundation" \
    "NFR-SEC-01 (SRS B1) — role-based access control over visitor personal data. Role/permission model itself is TDD-derived (FR-USR-02)." \
    "Role and permission model, deny-by-default authorization enforced at the API boundary, role administration, and tenant/floor data scoping." \
    "Ready — 4 features. F-03.4 blocked." \
    "TODO-14 — tenant data isolation rules are undefined. No document states whether Tenant A may see Tenant B's visitors, or whether a floor receptionist is limited to their floor. **This is a design-time decision; retrofitting it is expensive.**" \
    "F-03.1 Role & permission model · F-03.2 API-boundary enforcement · F-03.3 Role administration · F-03.4 Tenant & floor scoping ⛔"

  create_epic "[EPIC-04] Configuration & Master Data" "$M1" \
    "type/epic,priority/P0-critical,status/blocked,component/master-data,provenance/tdd-derived,blocked/srs-v2,phase/1-platform-foundation" \
    "**NONE in the attached SRS.** The TDD (§4.1) and schema cite FR-CFG-01..08 and FR-SET-01, which are undefined. See discrepancies D-03 and D-05." \
    "Master data: Building, Floor, Tenant, Reception, Visitor Type, Pass Type, Holiday Calendar, System Settings, plus Redis caching and invalidation." \
    "🔴 **BLOCKED — entirely TDD-derived.** Backlog is written and estimated so the project has a realistic shape, but implementation is not authorized until TODO-01 is dispositioned. If TODO-01 is unresolved at Phase 1 exit, a minimal master-data slice covering only what Phase 2 needs may be built as a documented deviation **with client sign-off**." \
    "TODO-01 — supply SRS v2, or confirm the attached SRS is the delivery baseline." \
    "F-04.1 Building · F-04.2 Floor · F-04.3 Tenant · F-04.4 Reception · F-04.5 Visitor type · F-04.6 Pass type · F-04.7 Holiday calendar · F-04.8 System settings · F-04.9 Caching & invalidation"

  create_epic "[EPIC-05] Audit, Security & Compliance Foundation" "$M1" \
    "type/epic,priority/P1-high,status/backlog,component/audit,security,privacy/pii,nfr,provenance/srs,phase/1-platform-foundation" \
    "FR-API-03 (SRS B1) for secrets; NFR-SEC-01, NFR-CMP-01 (SRS B1). Audit log itself is TDD-derived (FR-AUD-01)." \
    "Append-only audit log, secrets management, encryption in transit and at rest, data retention and purge, and OWASP ASVS baseline verification in CI." \
    "🟠 Partial — F-05.1 is TDD-derived, F-05.4 is blocked." \
    "TODO-01 (audit log has no SRS requirement) · TODO-12 (no retention period is defined anywhere, yet the system stores names, emails, phones and an ID document reference) · TODO-06 (hosting region and data residency)" \
    "F-05.1 Append-only audit log ⚠️ · F-05.2 Secrets management · F-05.3 Data protection · F-05.4 Retention & purge ⛔ · F-05.5 OWASP ASVS in CI"

  create_epic "[EPIC-06] Portal Shell & Design System" "$M1" \
    "type/epic,priority/P1-high,status/backlog,component/frontend,provenance/srs,phase/1-platform-foundation" \
    "FR-ADM-03 (SRS B1), CON-03 (SRS B1). Accessibility derived from NFR-USA-01." \
    "Design tokens and client branding, shared component library, role-driven routing shell serving all four user interfaces (tenant, admin, reception, visitor display), and a WCAG 2.1 AA baseline." \
    "Ready — 4 features. Not blocking: build against neutral tokens and swap when brand assets arrive." \
    "Client brand assets and guidelines have not been supplied. F-06.4 (WCAG 2.1 AA) is derived from NFR-USA-01, not stated — confirm with the client." \
    "F-06.1 Design tokens & branding · F-06.2 Component library · F-06.3 Role-driven routing shell · F-06.4 Accessibility baseline"

  # ---------------------------------------------------------- PHASE 2
  create_epic "[EPIC-07] Visitor Request & Approval" "$M2" \
    "type/epic,priority/P0-critical,status/backlog,component/visitor,privacy/pii,provenance/srs,phase/2-visitor-registration" \
    "FR-VMS-01 (SRS B1), FR-VMS-02 (SRS B1)." \
    "Tenant visitor request submission including group requests, the FM Admin approval dashboard, approve/reject with recorded reason, the request state machine and its domain events, and request status visibility for tenants." \
    "Ready — 6 features. **Core of the first client-demonstrable release.**" \
    "TODO-14 (tenant data isolation) affects what a tenant may see in the approval queue." \
    "F-07.1 Request submission · F-07.2 Group requests · F-07.3 Approval dashboard · F-07.4 Approve/reject with reason · F-07.5 State machine & events · F-07.6 Status visibility"

  create_epic "[EPIC-08] Visitor Pre-Registration" "$M2" \
    "type/epic,priority/P0-critical,status/backlog,component/visitor,privacy/pii,provenance/srs,phase/2-visitor-registration" \
    "FR-VMS-03 (SRS B1), FR-VMS-11 (SRS B1)." \
    "Floor receptionist pre-registration of visitor details, submission and synchronisation to the central server ahead of arrival, appointment scheduling with validity windows, and visitor record lifecycle and status tracking." \
    "Ready — 4 features." \
    "TODO-13 — the schema has \`visitors.id_document_ref\` but no SRS requirement mentions ID capture. If in scope it needs a requirement and a privacy assessment." \
    "F-08.1 Floor pre-registration · F-08.2 Central server sync · F-08.3 Appointment scheduling · F-08.4 Visitor lifecycle & status ⚠️"

  create_epic "[EPIC-09] Pass & Credential Generation" "$M2" \
    "type/epic,priority/P0-critical,status/backlog,component/credential,component/acs-integration,acs-stage/A-simulator,provenance/srs,phase/2-visitor-registration" \
    "FR-VMS-05 (SRS B1), FR-VMS-06 (SRS B1), FR-VMS-07 (SRS B1), FR-VMS-11 (SRS B1), FR-VMS-13 (SRS B1)." \
    "Credential creation request orchestration through the ACS port, storage of the returned credential reference, validity window and restriction type specification, QR rendering, and pass delivery by email, on-screen display and print." \
    "🟠 **Implementable against the ACS simulator; NOT verifiable until TODO-02 resolves.** Per ADR-0002 these stories reach *done against simulator* only. This distinction must be stated plainly in any client demo, or passes will be assumed to work at the gate." \
    "TODO-02 — verification only. Depends on the pulled-forward ACS port (F-11.1) and simulator (F-11.2) from Phase 1." \
    "F-09.1 Credential request orchestration · F-09.2 Reference storage · F-09.3 Validity window · F-09.4 Restriction type · F-09.5 QR rendering · F-09.6 Pass delivery · F-09.7 Lifecycle state machine ⚠️"

  create_epic "[EPIC-10] Host Management" "$M2" \
    "type/epic,priority/P2-medium,status/backlog,component/visitor,provenance/tdd-derived,blocked/srs-v2,phase/2-visitor-registration" \
    "**TDD-derived.** The schema defines \`vms.hosts\` and TDD §4.2 assigns host management to the Visitor & Approval Service, but the attached SRS has no host-management requirement." \
    "Host directory and host assignment to visitor requests." \
    "⚠️ TDD-derived — backlog only pending TODO-01. Note that FR-NOT-01 and FR-NOT-02 (SRS B1) both require notifying \"the host\", so *some* host concept is implied by the SRS even though its management is not specified." \
    "TODO-01." \
    "F-10.1 Host directory · F-10.2 Host assignment"

  # ---------------------------------------------------------- PHASE 3
  create_epic "[EPIC-11] ACS Integration Client (Anti-Corruption Layer)" "$M3" \
    "type/epic,priority/P0-critical,status/backlog,component/acs-integration,security,nfr,provenance/srs,phase/3-entry-acs" \
    "FR-API-01 (SRS B1), FR-API-02 (SRS B1), FR-API-03 (SRS B1). Enforces CON-01, CON-02, NFR-MNT-01, NFR-REL-01 (SRS B1)." \
    "The single choke-point for every ACS call: the port, the anti-corruption adapter, the simulator, retry with exponential backoff, the durable outbox, dead-letter handling, request/response audit logging, service authentication, idempotent inbound event handling, and the periodic state synchronisation job." \
    "🟠 **The highest-value work available right now.** Only F-11.7 (the wire-level adapter) is blocked. **F-11.1 and F-11.2 are pulled forward into Phase 1** — Phase 2 credential generation cannot be built or tested without them. See ADR-0002." \
    "TODO-02 blocks F-11.7 only. Everything else — including the simulator that unblocks EPIC-09, EPIC-12 and EPIC-15 — can start immediately." \
    "F-11.1 ACS port ⭐ · F-11.2 ACS simulator ⭐ · F-11.3 Retry & outbox · F-11.4 Dead-letter & alerting · F-11.5 API audit logging · F-11.6 ACS authentication · F-11.7 Wire adapter ⛔ · F-11.8 Inbound events & idempotency · F-11.9 Periodic sync"

  create_epic "[EPIC-12] Arrival Verification & Entry" "$M3" \
    "type/epic,priority/P0-critical,status/backlog,component/entry,privacy/pii,provenance/srs,phase/3-entry-acs" \
    "FR-VMS-04 (SRS B1), FR-VMS-15 (SRS B1). Check-in/out and event processing are TDD-derived (FR-ENT-06/07/10)." \
    "Arrival lookup and appointment confirmation at central reception, check-in/check-out status tracking, access and exit event processing from ACS, and the visitor-facing slave display." \
    "🟠 Partial — F-12.1 uses only VMS-owned data and can start once EPIC-08 lands." \
    "TODO-17 — the visitor display's physical and interaction model is undefined: dedicated device or second monitor, browser or native, how it pairs to a workstation, what it shows when idle, how it is secured against visitor interaction." \
    "F-12.1 Arrival lookup · F-12.2 Check-in/out tracking ⚠️ · F-12.3 Access & exit events ⚠️ · F-12.4 Visitor-facing display"

  create_epic "[EPIC-13] Walk-in & Express Entry" "$M3" \
    "type/epic,priority/P1-high,status/backlog,component/entry,descope-candidate,provenance/srs,phase/3-entry-acs" \
    "FR-VMS-08 (SRS B1), FR-VMS-09 (SRS B1), FR-VMS-10 (SRS B1)." \
    "Walk-in visitor registration at reception, host or FM Admin approval confirmation, walk-in credential request through the same ACS path as pre-scheduled visitors, and express entry allowing pre-scheduled visitors to scan at the barrier and bypass reception." \
    "🔴 **F-13.4 (express entry, FR-VMS-10) may be descoped entirely.** The SRS itself notes the vendor's current submission describes a fully reception-mediated flow. Do not plan capacity against it, and do not let other stories depend on it." \
    "TODO-03 (is express entry achievable — needs explicit vendor confirmation) · TODO-18 (walk-in approval channel: in-app action, recorded phone call, or either) · TODO-02" \
    "F-13.1 Walk-in registration · F-13.2 Approval confirmation · F-13.3 Walk-in credential request · F-13.4 Express entry ⛔ descope candidate"

  create_epic "[EPIC-14] Credential Lifecycle Operations" "$M3" \
    "type/epic,priority/P0-critical,status/backlog,component/credential,security,provenance/srs,phase/3-entry-acs" \
    "FR-VMS-12 (SRS B1), FR-VMS-14 (SRS B1)." \
    "Credential deactivation at end of validity window, on-demand status query with Redis caching, and credential cancellation and revocation." \
    "🟠 Partial. **F-14.4 (manual override) has NO SRS requirement and must NOT be built** — the TDD cites \"SRS Appendix B, Item 5\" but the attached SRS Appendix B has only 4 items (discrepancy D-06). It is also a security-sensitive forced state change." \
    "TODO-09 — FR-VMS-12 states VMS shall deactivate at end of validity *\"unless ACS performs this automatically and reports status back\"*. Both branches are stated as acceptable; the choice determines whether we build a scheduler. Design must tolerate both until TODO-02 reveals ACS behaviour. TODO-04 — is manual override in scope at all?" \
    "F-14.1 Deactivation at expiry · F-14.2 Status query & cache · F-14.3 Cancellation & revocation ⚠️ · F-14.4 Manual override ⛔ **do not build**"

  create_epic "[EPIC-15] Card Accountability & Reconciliation" "$M3" \
    "type/epic,priority/P1-high,status/backlog,component/card,provenance/srs,phase/3-entry-acs" \
    "FR-CRD-01 (SRS B1), FR-CRD-02 (SRS B1), FR-CRD-03 (SRS B1)." \
    "Logging each RFID card issued with the ACS-returned card identifier, logging returns from ACS-reported card-issuer events, daily issued-vs-returned reconciliation, and discrepancy flagging for missing cards." \
    "🔴 Depends entirely on ACS-reported card events — Stage A against the simulator, Stage B for real." \
    "TODO-02 · TODO-11 — reconciliation specifics are undefined: run time and timezone, the cut-off defining a \"day\", who receives the discrepancy flag and through which channel, and what happens to a card still unreturned at the day boundary." \
    "F-15.1 Card issuance logging · F-15.2 Card return logging · F-15.3 Daily reconciliation · F-15.4 Discrepancy flagging"

  # ---------------------------------------------------------- PHASE 4
  create_epic "[EPIC-16] Notification Platform" "$M4" \
    "type/epic,priority/P1-high,status/backlog,component/notification,privacy/pii,provenance/srs,phase/4-reporting-notification" \
    "FR-NOT-01 (SRS B1) for the email and WhatsApp channels. The service architecture itself is TDD-derived (TDD §8)." \
    "Event-driven notification service subscribing to domain events, the email channel with delivery status logging, the WhatsApp channel, notification templates, and channel selection." \
    "🟠 **Email path can and should be pulled forward into Phase 2** — it depends only on VMS domain events, and converts any TODO-02 delay into shipped requirement coverage." \
    "TODO-05 (WhatsApp Business API not approved — the schema already defaults \`notification.whatsapp.enabled\` to false; build behind a disabled flag) · TODO-10 (FR-NOT-01 says \"email and/or WhatsApp\" without saying who chooses: per-recipient preference, per-tenant policy, or global setting)" \
    "F-16.1 Service & event subscription ⚠️ · F-16.2 Email channel · F-16.3 WhatsApp channel ⛔ · F-16.4 Templates ⚠️ · F-16.5 Channel selection ⛔"

  create_epic "[EPIC-17] Notification Scenarios" "$M4" \
    "type/epic,priority/P1-high,status/backlog,component/notification,privacy/pii,provenance/srs,phase/4-reporting-notification" \
    "FR-NOT-01 (SRS B1), FR-NOT-02 (SRS B1). FR-NOT-03/04/05 are TDD-derived and undefined in the SRS." \
    "Credential confirmation to visitor and host including the QR code and welcome message, exit alerts to host and visitor on ACS exit events, host notifications on approval/entry/exit, appointment reminders, and system alerts to administrators." \
    "🟠 Partial — only F-17.1 and F-17.2 are SRS-backed. F-17.3, F-17.4 and F-17.5 are TDD-derived and need TODO-01 disposition." \
    "TODO-01 (F-17.3/4/5 have no SRS requirement) · TODO-02 (exit alerts depend on ACS exit events)" \
    "F-17.1 Credential confirmation · F-17.2 Exit alerts · F-17.3 Host notifications ⚠️ · F-17.4 Reminders ⚠️ · F-17.5 System alerts ⚠️"

  create_epic "[EPIC-18] Reporting Suite" "$M4" \
    "type/epic,priority/P1-high,status/backlog,component/reporting,privacy/pii,provenance/srs,phase/4-reporting-notification" \
    "FR-REP-01 (SRS B1), FR-REP-02 (SRS B1). FR-REP-06 (audit report) is TDD-derived." \
    "Automatic daily, weekly and monthly visitor activity reports combining VMS data with ACS-sourced events; the end-of-day credentials-and-cards issued/returned report reconciled against card accountability; report scheduling; and the audit report." \
    "🟠 Partial — report structure is buildable; the ACS-sourced half of the data is gated on TODO-02." \
    "TODO-02 (ACS access and exit events) · TODO-01 (F-18.4 audit report is TDD-derived)" \
    "F-18.1 Activity reports · F-18.2 End-of-day report · F-18.3 Scheduling · F-18.4 Audit report ⚠️"

  create_epic "[EPIC-19] Analytics & Export" "$M4" \
    "type/epic,priority/P3-low,status/blocked,component/analytics,provenance/tdd-derived,blocked/srs-v2,phase/4-reporting-notification" \
    "**NONE in the attached SRS.** FR-ANL-01 and FR-EXP-01 are cited by the TDD (§3, §4.5) but undefined. SRS §3.8 requires neither an analytics dashboard nor Excel/PDF export." \
    "Analytics dashboard and Excel/PDF export." \
    "🔴 **BLOCKED — entirely TDD-derived.** Lowest priority in the plan: it is the largest body of work with the least requirement backing." \
    "TODO-01 · TODO-16 (confirm whether Excel/PDF export is in scope for release 1.0)" \
    "F-19.1 Analytics dashboard ⚠️ · F-19.2 Excel & PDF export ⚠️"

  echo "  → $n_epics epics"; echo
fi

# ---------------------------------------------------------------- next steps
c_info "Done."
cat <<'EOF'

Remaining setup (needs repository admin — cannot be scripted via gh):

  1. CODEOWNERS uses org team handles (@pantropi/vms-*). On a personal repo these do NOT
     resolve, so every review rule silently fails. Either move the repo under a `pantropi`
     organisation, or replace them with individual usernames.

  2. Push and set the default branch:
       git push -u origin main develop
       # then set `develop` as default in Settings → Branches

  3. Enable branch protection on `main` and `develop`:
       docs/project/10-branch-strategy.md#branch-protection

  4. Create the GitHub Project (v2) board "Project Pinnacle VMS — Delivery" with columns:
       Backlog · Ready · In Progress · Code Review · Testing · Ready for Staging · Done
     Custom fields, views and automation: docs/project/11-labels-and-project-board.md
     WIP limit on In Progress is 1 PER DEVELOPER — this is the "one user story at a time" rule.

  5. Raise Spike issues for the 4 BLOCKING open questions and assign owners:
       TODO-01  SRS v2 missing .......... CPG / Pantropi document control
       TODO-02  ACS API contract ........ Universal Automations Ltd
       TODO-03  Express entry feasible? . Universal Automations Ltd
       TODO-19  Gate decision authority . CPG / UAL

  6. Create feature and user story issues per phase AT SPRINT PLANNING, from:
       docs/project/backlog/phase-1-platform-foundation.md
       docs/project/backlog/phase-2-visitor-registration-pass-generation.md
       docs/project/backlog/phase-3-entry-verification-acs-integration.md
       docs/project/backlog/phase-4-reporting-notification.md
     Do not bulk-create all of them now: estimates go stale, and opening blocked work
     as `status/ready` invites someone to start it.

EOF
