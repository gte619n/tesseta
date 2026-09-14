#!/usr/bin/env bash
#
# apply-branch-ruleset.sh — apply the `main` branch protection ruleset (CICD-001).
#
# WHAT THIS DOES
#   Creates (or updates) the repository ruleset defined in
#   .github/branch-ruleset.json, which:
#     - requires a pull request before merging to main (0 required approvals —
#       the gate is the status checks, not review, for a solo operator);
#     - requires these status checks to pass before merge:
#         android-ci, backend-ci, web-ci, terraform-ci,
#         analyze (javascript-typescript), analyze (java-kotlin)
#     - blocks branch deletion and force-pushes to main;
#     - lets repository Admins bypass (break-glass) — enforcement is NOT applied
#       to admins by design (see .github/README.md, DEC-CICD-001-admin).
#
# *** RUN ORDER — READ THIS FIRST ***
#   Per lead-agent decision DEC-001, this script is NOT auto-applied. Apply it
#   MANUALLY, and ONLY AFTER the refactored workflows have merged to main.
#   Reason: the required status checks above (android-ci / backend-ci / web-ci /
#   terraform-ci as aggregate job names) only exist once the refactored
#   .github/workflows/*.yml are on main. If you enable the ruleset while main's
#   workflows still define the OLD job names, every PR's required checks will
#   reference contexts that never report and PRs will be permanently un-mergeable
#   (a self-inflicted lockout). The Admin bypass in the ruleset is your recovery
#   path if that happens — but the correct sequence avoids it entirely:
#     1. Merge the workflow refactor (this branch) to main.
#     2. Open one throwaway PR touching each component (or trust the first real
#        PRs) and confirm each of the 6 checks reports a conclusion.
#     3. THEN run this script.
#
# REQUIREMENTS
#   - gh CLI authenticated with `repo` + admin scope on the repo
#     (`gh auth status`; the token needs Administration:write to manage rulesets).
#   - jq (only used for pretty-printing; not required to apply).
#
# IDEMPOTENCY
#   Re-running is safe: if a ruleset with the same name already exists, the
#   script updates it in place (PUT) instead of creating a duplicate (POST).
#
set -euo pipefail

REPO="${REPO:-gte619n/tesseta}"
PAYLOAD="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/branch-ruleset.json"
RULESET_NAME="$(grep -m1 '"name"' "$PAYLOAD" | sed -E 's/.*"name" *: *"([^"]+)".*/\1/')"

echo "Repo:         $REPO"
echo "Payload:      $PAYLOAD"
echo "Ruleset name: $RULESET_NAME"
echo

# --- READ-ONLY: list current rulesets first ------------------------------------
echo "== Current repository rulesets (read-only) =="
gh api "repos/$REPO/rulesets" \
  --jq '.[] | {id, name, target, enforcement}' || true
echo

# --- Find an existing ruleset with our name ------------------------------------
EXISTING_ID="$(gh api "repos/$REPO/rulesets" \
  --jq ".[] | select(.name == \"$RULESET_NAME\") | .id" 2>/dev/null | head -n1 || true)"

if [[ -n "${EXISTING_ID:-}" ]]; then
  echo "Ruleset '$RULESET_NAME' already exists (id=$EXISTING_ID) — updating in place (PUT)."
  read -r -p "Proceed with UPDATE? [y/N] " ans
  [[ "$ans" == "y" || "$ans" == "Y" ]] || { echo "Aborted."; exit 1; }
  gh api --method PUT "repos/$REPO/rulesets/$EXISTING_ID" --input "$PAYLOAD" \
    --jq '{id, name, enforcement, updated_at}'
else
  echo "No existing ruleset named '$RULESET_NAME' — creating (POST)."
  read -r -p "Proceed with CREATE? [y/N] " ans
  [[ "$ans" == "y" || "$ans" == "Y" ]] || { echo "Aborted."; exit 1; }
  gh api --method POST "repos/$REPO/rulesets" --input "$PAYLOAD" \
    --jq '{id, name, enforcement, created_at}'
fi

echo
echo "Done. Verify with:"
echo "  gh api repos/$REPO/rulesets --jq '.[] | {id,name,enforcement}'"
echo "  gh api repos/$REPO/rulesets/<id> --jq '.rules'"
