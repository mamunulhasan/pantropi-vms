#!/usr/bin/env bash
#
# test-branch-protection-config.sh — UNIT TESTS for the branch-protection config.
#
# US-01.1.1 · T-01.1.1.3
#
# Runs offline: no network, no GitHub, no auth. Validates that the committed JSON is
# well-formed and internally consistent BEFORE anyone applies it to a live branch.
# The companion integration test (verify-branch-protection.sh) checks the live state.
#
# Usage: ./scripts/test-branch-protection-config.sh
# Exit:  0 pass · 1 fail

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# Host Python may default to a non-UTF-8 stdout (Windows cp1252), which
# crashes on the symbols below. Force UTF-8 for every embedded script.
export PYTHONIOENCODING=utf-8
PY=$(command -v python3 || command -v python) || { echo "python not found." >&2; exit 1; }

"$PY" - <<'PYEOF'
import json,sys,os
FAILS=[];PASSES=0
def ok(m):
    global PASSES; PASSES+=1; print(f"  \033[32m✓\033[0m {m}")
def bad(m):
    FAILS.append(m); print(f"  \033[31m✗\033[0m {m}")
def check(cond,m):
    ok(m) if cond else bad(m)

print("\033[36m▸\033[0m Unit tests — branch-protection configuration\n")
cfgs={}
for b in ("main","develop"):
    p=f".github/branch-protection/{b}.json"
    if not os.path.exists(p): bad(f"{b}: config file exists"); continue
    try:
        cfgs[b]=json.load(open(p,encoding='utf-8')); ok(f"{b}: config file is valid JSON")
    except Exception as e:
        bad(f"{b}: config file is valid JSON — {e}")

print()
REQUIRED=["required_status_checks","enforce_admins","required_pull_request_reviews",
          "required_conversation_resolution","required_linear_history",
          "allow_force_pushes","allow_deletions"]
for b,c in cfgs.items():
    missing=[k for k in REQUIRED if k not in c]
    check(not missing, f"{b}: all required keys present" + (f" (missing {missing})" if missing else ""))

print()
for b,c in cfgs.items():
    # AC-4 — admins must be included, or the whole protection is theatre
    check(c.get("enforce_admins") is True, f"{b}: enforce_admins is true (AC-4)")
    # Destructive operations must stay off on protected branches
    check(c.get("allow_force_pushes") is False, f"{b}: force pushes disabled")
    check(c.get("allow_deletions")    is False, f"{b}: deletions disabled")
    # AC-2 — up to date before merging
    check(c.get("required_status_checks",{}).get("strict") is True,
          f"{b}: status checks strict — branch must be up to date (AC-2)")
    # AC-2 — conversations resolved
    check(c.get("required_conversation_resolution") is True,
          f"{b}: conversation resolution required (AC-2)")
    # AC-5 — stale approvals dismissed
    check(c.get("required_pull_request_reviews",{}).get("dismiss_stale_reviews") is True,
          f"{b}: stale reviews dismissed (AC-5)")

print()
# --- The consistency rule that caught a real contradiction -------------------
# main takes --no-ff merges from release/* and hotfix/* (branch strategy §Lifecycle).
# required_linear_history forbids merge commits. Both cannot be true.
if "main" in cfgs:
    check(cfgs["main"].get("required_linear_history") is False,
          "main: linear history FALSE — release/hotfix merge with --no-ff would otherwise fail")
if "develop" in cfgs:
    check(cfgs["develop"].get("required_linear_history") is True,
          "develop: linear history TRUE — feature branches squash-merge")

print()
# --- Deviation must be explicit, never silent --------------------------------
for b,c in cfgs.items():
    n=c.get("required_pull_request_reviews",{}).get("required_approving_review_count")
    if n==0:
        has_note=any(k.startswith("_") and "DEVIATION" in str(v).upper()
                     for k,v in c.get("required_pull_request_reviews",{}).items()) \
                 or any("DEVIATION" in str(v).upper() for k,v in c.items() if k.startswith("_"))
        check(has_note, f"{b}: approvals=0 is documented as an explicit DEVIATION, not silent")
    else:
        ok(f"{b}: approvals required = {n}")

print()
# --- Empty required contexts is a real risk; it must be acknowledged ---------
for b,c in cfgs.items():
    ctx=c.get("required_status_checks",{}).get("contexts",[])
    if not ctx:
        noted=any("contexts is intentionally EMPTY" in str(v) or "CI exists" in str(v)
                  for k,v in c.items() if k.startswith("_"))
        check(noted, f"{b}: empty required contexts is acknowledged in config (no CI until US-01.4.x)")
    else:
        ok(f"{b}: {len(ctx)} required status check(s)")

print()
print("\033[36m▸\033[0m Result")
if FAILS:
    print(f"  \033[31m{len(FAILS)} assertion(s) failed\033[0m of {PASSES+len(FAILS)}")
    for f in FAILS: print(f"    - {f}")
    sys.exit(1)
print(f"  \033[32mAll {PASSES} assertions passed\033[0m")
PYEOF
