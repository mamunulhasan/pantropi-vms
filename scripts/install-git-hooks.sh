#!/usr/bin/env bash
#
# install-git-hooks.sh — install the local commit-msg hook.
#
# US-01.1.2 · T-01.1.2.1 — optional but recommended: catches a bad message at
# commit time instead of at CI time. The hook simply delegates to
# scripts/lint-commit-message.sh, so local and CI rules are identical.
#
# Usage: ./scripts/install-git-hooks.sh

set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOOK="$ROOT/.git/hooks/commit-msg"

cat > "$HOOK" <<'EOF'
#!/usr/bin/env bash
# Installed by scripts/install-git-hooks.sh — delegates to the shared linter.
exec "$(git rev-parse --show-toplevel)/scripts/lint-commit-message.sh" "$1"
EOF
chmod +x "$HOOK"
echo "commit-msg hook installed -> $HOOK"
echo "Bypass in an emergency with: git commit --no-verify (CI will still enforce)"
