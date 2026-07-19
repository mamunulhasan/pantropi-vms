#!/usr/bin/env bash
#
# lint-commit-message.sh — Conventional Commits + traceability lint.
#
# US-01.1.2 · T-01.1.2.1 — single source of truth for commit-message rules.
# Used by BOTH the local commit-msg hook (scripts/install-git-hooks.sh) and the
# CI check (.github/workflows/commit-lint.yml), so the rules cannot drift apart.
#
# Rules (workflow doc §3; ACs of US-01.1.2):
#   R1  Subject:  <type>(<scope>): <subject>   — type in allowed set        (AC-1)
#   R2  Scope in the TDD §4 list when present; feat/fix REQUIRE a scope     (AC-1)
#   R3  Subject: lowercase start, imperative, ≤72 chars, no trailing period (AC-1)
#   R4  feat/fix must reference a user story id  US-NN.N.N                  (AC-2)
#   R5  Any line citing FR-XXX-NN must qualify it with (SRS Bn) — D-02      (AC-4)
#
# Skipped (not authored by hand): "Merge ..." commits, GitHub squash/revert
# subjects ending in "(#N)", and fixup!/squash! autosquash commits.
#
# Usage:  lint-commit-message.sh <file-with-message>      (hook mode)
#         echo "msg" | lint-commit-message.sh -           (stdin)
# Exit:   0 pass · 1 fail (each failure names its rule)

set -euo pipefail
export PYTHONIOENCODING=utf-8
PY=$(command -v python3 || command -v python) || { echo "python not found." >&2; exit 1; }

SRC="${1:?usage: lint-commit-message.sh <file|->}"
if [[ "$SRC" == "-" ]]; then MSG="$(cat)"; else MSG="$(cat "$SRC")"; fi

"$PY" - "$MSG" <<'PYEOF'
import re, sys

msg = sys.argv[1].replace('\r\n', '\n')
lines = msg.split('\n')
subject = lines[0] if lines else ''
fails = []

TYPES  = {'feat','fix','docs','test','refactor','perf','build','ci','chore','revert'}
SCOPES = {'master-data','visitor','credential','entry','acs','notification','reporting',
          'identity','audit','frontend','platform','db',
          # governance scopes used by project/tooling commits
          'project','backlog','scripts','trace'}

# ---- Skips: machine-generated messages --------------------------------------
if (subject.startswith('Merge ')
        or re.search(r'\(#\d+\)$', subject)          # GitHub squash/merge suffix
        or subject.startswith(('fixup!','squash!'))
        or subject.startswith('Revert "')):
    print(f"SKIP (machine-generated): {subject[:60]}")
    sys.exit(0)

# ---- R1/R2: type(scope): subject --------------------------------------------
m = re.match(r'^(\w+)(?:\(([a-z0-9-]+)\))?(!)?: (.+)$', subject)
if not m:
    fails.append("R1: subject must be '<type>(<scope>): <subject>' — got: " + subject[:72])
else:
    ctype, scope, _, subj = m.groups()
    if ctype not in TYPES:
        fails.append(f"R1: type '{ctype}' not in allowed set {sorted(TYPES)}")
    if scope and scope not in SCOPES:
        fails.append(f"R2: scope '{scope}' not in the TDD §4 scope list {sorted(SCOPES)}")
    if ctype in ('feat','fix') and not scope:
        fails.append(f"R2: '{ctype}' commits require a scope from the TDD §4 list")
    # ---- R3: subject shape ----
    if len(subject) > 72:
        fails.append(f"R3: subject is {len(subject)} chars — limit 72")
    if subj and subj[0].isupper():
        fails.append("R3: subject must start lowercase (imperative mood)")
    if subj.rstrip().endswith('.'):
        fails.append("R3: subject must not end with a period")
    # ---- R4: story id on feat/fix (AC-2) ----
    if ctype in ('feat','fix') and not re.search(r'\bUS-\d{2}\.\d+\.\d+\b', msg):
        fails.append("R4 (AC-2): feat/fix must reference a user story id 'US-NN.N.N' "
                      "in the subject or body")

# ---- R5: qualified requirement references — discrepancy D-02 (AC-4) ---------
# FR ids were renumbered between SRS versions; a bare id is ambiguous. Any line
# that cites an FR id must also carry an '(SRS Bn)' qualifier on that line
# (a line-level rule so ranges like 'FR-VMS-01..15 (SRS B1)' pass naturally).
for i, line in enumerate(lines):
    if re.search(r'\bFR-[A-Z]{3}-\d{2}\b', line) and not re.search(r'\(SRS B\d+\)', line):
        fails.append(f"R5 (AC-4, D-02): line {i+1} cites a requirement id without an "
                     f"'(SRS Bn)' qualifier: {line.strip()[:60]}")

if fails:
    print("COMMIT MESSAGE REJECTED:")
    for f in fails:
        print(f"  ✗ {f}")
    print("\nFormat:  <type>(<scope>): <subject>  … body …  (US-NN.N.N)")
    print("Rules:   docs/project/05-workflow-and-branching.md §3")
    sys.exit(1)

print(f"OK: {subject[:72]}")
PYEOF
