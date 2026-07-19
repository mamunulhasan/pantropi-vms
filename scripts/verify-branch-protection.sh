#!/usr/bin/env bash
#
# verify-branch-protection.sh — assert live protection matches committed config.
#
# US-01.1.1 · T-01.1.1.3 — this is the INTEGRATION TEST for AC-2, AC-4 and AC-5.
#
# Infrastructure-as-config needs the same discipline as code: the committed JSON is a
# claim, and this script is the test that the claim is true. It fails loudly on drift —
# someone changing protection through the GitHub UI is exactly what this catches.
#
# Requires: gh (authenticated), python3
# Usage:    ./scripts/verify-branch-protection.sh [--repo owner/name]
# Exit:     0 all assertions pass · 1 drift or missing protection

set -euo pipefail

REPO=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PASS=0; FAIL=0
p_ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; PASS=$((PASS+1)); }
p_fail() { printf '  \033[31m✗\033[0m %s\n' "$*"; FAIL=$((FAIL+1)); }
c_info() { printf '\033[36m▸\033[0m %s\n' "$*"; }

command -v gh >/dev/null 2>&1 || { echo "gh CLI not found." >&2; exit 1; }
# Host Python may default to a non-UTF-8 stdout (Windows cp1252), which
# crashes on the symbols below. Force UTF-8 for every embedded script.
export PYTHONIOENCODING=utf-8
PY=$(command -v python3 || command -v python) || { echo "python not found." >&2; exit 1; }
[[ -n "$REPO" ]] || REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"

c_info "Verifying branch protection on $REPO"
echo

check_branch() {
  local branch="$1"
  local cfg=".github/branch-protection/${branch}.json"
  c_info "Branch: $branch"

  [[ -f "$cfg" ]] || { p_fail "$branch — config file missing at $cfg"; return; }

  local live
  if ! live="$(gh api "repos/$REPO/branches/$branch/protection" 2>/dev/null)"; then
    p_fail "$branch — NOT PROTECTED (or inaccessible)"
    return
  fi

  "$PY" - "$cfg" "$live" "$branch" <<'PYEOF'
import json,sys
cfg=json.load(open(sys.argv[1],encoding='utf-8'))
live=json.loads(sys.argv[2]); branch=sys.argv[3]
ok=lambda m: print(f"  \033[32m✓\033[0m {m}")
no=lambda m: print(f"  \033[31m✗\033[0m {m}")
fails=0
def eq(label, want, got):
    global fails
    if want==got: ok(f"{label}: {got}")
    else:         no(f"{label}: expected {want}, got {got}"); fails+=1

# AC-4 — administrators included
eq("enforce_admins", cfg["enforce_admins"], live.get("enforce_admins",{}).get("enabled"))

# AC-2 — up-to-date branch required
want_strict=cfg["required_status_checks"]["strict"]
eq("status checks strict (up-to-date)", want_strict,
   live.get("required_status_checks",{}).get("strict"))

want_ctx=sorted(cfg["required_status_checks"]["contexts"])
got_ctx=sorted(live.get("required_status_checks",{}).get("contexts",[]))
eq("required check contexts", want_ctx, got_ctx)
if not want_ctx:
    print("  \033[33m!\033[0m required contexts empty — no CI yet (US-01.4.x). "
          "Merges are NOT gated on tests. Expected at this stage.")

# AC-2 — conversation resolution
eq("required_conversation_resolution", cfg["required_conversation_resolution"],
   live.get("required_conversation_resolution",{}).get("enabled"))

# AC-5 — stale approvals dismissed
r_cfg=cfg["required_pull_request_reviews"]; r_live=live.get("required_pull_request_reviews",{})
eq("dismiss_stale_reviews", r_cfg["dismiss_stale_reviews"], r_live.get("dismiss_stale_reviews"))

# AC-2 / AC-3 — DEVIATION, asserted at its deviated value so drift is still caught
eq("required_approving_review_count", r_cfg["required_approving_review_count"],
   r_live.get("required_approving_review_count"))
eq("require_code_owner_reviews", r_cfg["require_code_owner_reviews"],
   r_live.get("require_code_owner_reviews"))
if r_cfg["required_approving_review_count"]==0:
    print("  \033[33m!\033[0m approvals=0 — DEVIATION from AC-2/AC-3. Single-owner repo: "
          "GitHub cannot request review from a PR author. See docs/project/13-branch-protection.md")

# History model
eq("required_linear_history", cfg["required_linear_history"],
   live.get("required_linear_history",{}).get("enabled"))

# Destructive operations
eq("allow_force_pushes", cfg["allow_force_pushes"], live.get("allow_force_pushes",{}).get("enabled"))
eq("allow_deletions",    cfg["allow_deletions"],    live.get("allow_deletions",{}).get("enabled"))

sys.exit(1 if fails else 0)
PYEOF
  if [[ $? -ne 0 ]]; then FAIL=$((FAIL+1)); else PASS=$((PASS+1)); fi
  echo
}

check_branch main
check_branch develop

c_info "Result"
if [[ $FAIL -eq 0 ]]; then
  printf '  \033[32mAll assertions passed\033[0m (%d branch checks)\n' "$PASS"
  exit 0
else
  printf '  \033[31m%d branch check(s) failed\033[0m — live protection has drifted from committed config.\n' "$FAIL"
  printf '  Restore with: ./scripts/apply-branch-protection.sh\n'
  exit 1
fi
