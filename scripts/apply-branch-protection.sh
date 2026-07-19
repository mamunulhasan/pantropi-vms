#!/usr/bin/env bash
#
# apply-branch-protection.sh — apply branch protection from version-controlled config.
#
# US-01.1.1 · T-01.1.1.3
#
# Reads .github/branch-protection/<branch>.json, strips the `_*` documentation keys,
# and PUTs the result to the GitHub branch-protection API. The committed JSON is the
# source of truth: protection is reviewable in a PR and restorable after any drift.
#
# Requires: gh (authenticated, `repo` scope), python3
#
# Usage:
#   ./scripts/apply-branch-protection.sh [--dry-run] [--repo owner/name] [--branch NAME]
#
# Verify afterwards with: ./scripts/verify-branch-protection.sh

set -euo pipefail

DRY_RUN=0
REPO=""
ONLY_BRANCH=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1 ;;
    --repo)    REPO="$2"; shift ;;
    --branch)  ONLY_BRANCH="$2"; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
  shift
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

c_ok()   { printf '\033[32m✓\033[0m %s\n' "$*"; }
c_info() { printf '\033[36m▸\033[0m %s\n' "$*"; }
c_warn() { printf '\033[33m!\033[0m %s\n' "$*"; }
c_err()  { printf '\033[31m✗\033[0m %s\n' "$*" >&2; }

command -v gh >/dev/null 2>&1 || { c_err "gh CLI not found."; exit 1; }
gh auth status >/dev/null 2>&1 || { c_err "gh is not authenticated. Run: gh auth login"; exit 1; }
# Host Python may default to a non-UTF-8 stdout (Windows cp1252), which
# crashes on the symbols below. Force UTF-8 for every embedded script.
export PYTHONIOENCODING=utf-8
PY=$(command -v python3 || command -v python) || { c_err "python not found."; exit 1; }

[[ -n "$REPO" ]] || REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"

c_info "Repository: $REPO"
[[ $DRY_RUN -eq 1 ]] && c_warn "DRY RUN — nothing will be changed."
echo

apply_branch() {
  local branch="$1"
  local cfg=".github/branch-protection/${branch}.json"
  [[ -f "$cfg" ]] || { c_warn "$branch — no config at $cfg, skipping"; return 0; }

  # Strip documentation keys (any key starting with _) at every level.
  local payload
  payload="$("$PY" - "$cfg" <<'PYEOF'
import json,sys
def strip(o):
    if isinstance(o,dict):  return {k:strip(v) for k,v in o.items() if not k.startswith('_')}
    if isinstance(o,list):  return [strip(v) for v in o]
    return o
print(json.dumps(strip(json.load(open(sys.argv[1],encoding='utf-8')))))
PYEOF
)"

  if [[ $DRY_RUN -eq 1 ]]; then
    printf '\033[90m  would PUT to repos/%s/branches/%s/protection:\033[0m\n' "$REPO" "$branch"
    "$PY" -c "import json,sys;print('\n'.join('    '+l for l in json.dumps(json.loads(sys.argv[1]),indent=2).split('\n')))" "$payload"
    return 0
  fi

  if printf '%s' "$payload" | gh api -X PUT "repos/$REPO/branches/$branch/protection" \
       -H "Accept: application/vnd.github+json" --input - >/dev/null 2>&1; then
    c_ok "$branch — protection applied"
  else
    c_err "$branch — failed"
    c_err "  Common causes: branch does not exist on the remote; token lacks 'repo' scope;"
    c_err "  or private repo on a plan without branch protection (public repos are fine)."
    return 1
  fi
}

FAILED=0
if [[ -n "$ONLY_BRANCH" ]]; then
  apply_branch "$ONLY_BRANCH" || FAILED=$((FAILED+1))
else
  apply_branch main    || FAILED=$((FAILED+1))
  apply_branch develop || FAILED=$((FAILED+1))
fi

echo
if [[ $FAILED -gt 0 ]]; then
  c_warn "$FAILED branch(es) not protected."
  c_warn "If the API said 'Upgrade to GitHub Pro or make this repository public':"
  c_warn "  branch protection is unavailable on private repositories on the free plan."
  c_warn "  See docs/project/13-branch-protection.md for the options and their trade-offs."
  exit 1
fi
c_info "Done. Verify with: ./scripts/verify-branch-protection.sh"
