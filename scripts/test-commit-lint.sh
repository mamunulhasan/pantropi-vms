#!/usr/bin/env bash
#
# test-commit-lint.sh — UNIT TESTS for scripts/lint-commit-message.sh
#
# US-01.1.2 · T-01.1.2.1 — offline, no network. Each case maps to an AC.
# Usage: ./scripts/test-commit-lint.sh   ·   Exit: 0 pass · 1 fail

set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINT="$ROOT/scripts/lint-commit-message.sh"
PASS=0; FAIL=0

expect() {  # expect <pass|fail> <label> <<< message
  local want="$1" label="$2" rc
  if bash "$LINT" - >/dev/null 2>&1; then rc=pass; else rc=fail; fi
  if [[ "$rc" == "$want" ]]; then
    printf '  \033[32m✓\033[0m %s\n' "$label"; PASS=$((PASS+1))
  else
    printf '  \033[31m✗\033[0m %s — expected %s, got %s\n' "$label" "$want" "$rc"; FAIL=$((FAIL+1))
  fi
}

printf '\033[36m▸\033[0m Unit tests — commit-message linter\n\n'

# ---- AC-1: conforming message passes ----------------------------------------
expect pass "AC-1: canonical feat with scope, story id" \
  <<< 'feat(identity): issue jwt on login (US-02.1.1)'
expect pass "AC-1: fix with body story id and qualified ref" \
  <<< 'fix(acs): retry queue drops request (US-11.3.1)

Requirement: FR-API-01 (SRS B1)'
expect pass "AC-1: docs commit needs no scope or story id" \
  <<< 'docs: clarify branch strategy'
expect pass "AC-1: breaking-change marker accepted" \
  <<< 'feat(acs)!: replace credential dto (US-11.7.1)'

# ---- AC-1: malformed subjects fail -------------------------------------------
expect fail "AC-1: unknown type rejected" \
  <<< 'feature(identity): issue jwt (US-02.1.1)'
expect fail "AC-1: unknown scope rejected" \
  <<< 'feat(login): issue jwt (US-02.1.1)'
expect fail "AC-1: uppercase subject rejected" \
  <<< 'feat(identity): Issue jwt on login (US-02.1.1)'
expect fail "AC-1: trailing period rejected" \
  <<< 'feat(identity): issue jwt on login (US-02.1.1).'
expect fail "AC-1: >72-char subject rejected" \
  <<< 'feat(identity): issue a json web token on login and also refresh it when it grows stale (US-02.1.1)'
expect fail "AC-1: missing colon structure rejected" \
  <<< 'added jwt support'

# ---- AC-2: story id required on feat/fix -------------------------------------
expect fail "AC-2: feat without story id fails" \
  <<< 'feat(identity): issue jwt on login'
expect fail "AC-2: fix without story id fails" \
  <<< 'fix(entry): correct check-in timestamp'
expect pass "AC-2: story id in body suffices" \
  <<< 'feat(identity): issue jwt on login

Story: US-02.1.1'
expect fail "AC-2: malformed story id (US-2.1.1) fails" \
  <<< 'feat(identity): issue jwt on login (US-2.1.1)'
expect fail "AC-2: feat without scope fails" \
  <<< 'feat: issue jwt on login (US-02.1.1)'

# ---- AC-4: bare requirement reference fails (D-02) ----------------------------
expect fail "AC-4: bare FR-ADM-02 in body fails" \
  <<< 'feat(identity): issue jwt on login (US-02.1.1)

Implements FR-ADM-02 for reception logins.'
expect pass "AC-4: qualified FR-ADM-02 (SRS B1) passes" \
  <<< 'feat(identity): issue jwt on login (US-02.1.1)

Implements FR-ADM-02 (SRS B1) for reception logins.'
expect pass "AC-4: range on a qualified line passes" \
  <<< 'feat(platform): baseline schema (US-01.5.1)

Refs: FR-VMS-01..15 covered by baseline (SRS B1)'
expect fail "AC-4: one bare ref among qualified lines still fails" \
  <<< 'feat(identity): issue jwt on login (US-02.1.1)

Requirement: FR-ADM-02 (SRS B1)
Also touches FR-ADM-01 incidentally.'

# ---- Skips: machine-generated messages ---------------------------------------
expect pass "skip: merge commit ignored" \
  <<< 'Merge pull request #26 from mamunulhasan/docs/backlog-fixes-missed-in-pr1'
expect pass "skip: GitHub squash suffix (#27) ignored" \
  <<< 'feat(platform): repository governance & branch protection (US-01.1.1) (#27)'
expect pass "skip: fixup! ignored" \
  <<< 'fixup! feat(identity): issue jwt on login'

printf '\n\033[36m▸\033[0m Result\n'
if [[ $FAIL -eq 0 ]]; then
  printf '  \033[32mAll %d assertions passed\033[0m\n' "$PASS"; exit 0
else
  printf '  \033[31m%d of %d assertions failed\033[0m\n' "$FAIL" "$((PASS+FAIL))"; exit 1
fi
