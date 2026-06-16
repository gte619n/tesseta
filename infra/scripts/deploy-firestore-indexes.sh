#!/usr/bin/env bash
# Deploy infra/firestore/firestore.indexes.json to a Firestore database.
#
# Why this exists: Firestore export/import (see
# copy-default-to-production-firestore.sh) copies DOCUMENTS only — it does NOT
# carry composite indexes or field overrides. After the `(default)` -> `production`
# migration the live database had data but was missing composite indexes and
# COLLECTION_GROUP field overrides, so queries that need them failed at runtime
# with FAILED_PRECONDITION ("the query requires an index"). This script
# reconciles the live database with the JSON source of truth so the two cannot
# silently drift again.
#
# It uses the firebase CLI rather than `gcloud firestore indexes`, because gcloud
# cannot express COLLECTION_GROUP scope for single-field overrides — firebase
# deploys the JSON natively (composite indexes + field overrides + scopes) and
# targets a named database. The CLI authenticates via gcloud Application Default
# Credentials; run `gcloud auth application-default login` first if needed.
#
# Reconciliation is additive for anything already present; with --force, indexes
# in the live database but absent from the JSON are DELETED (the file is the
# source of truth). Composite indexes build asynchronously (CREATING -> READY).
#
# Caveat: firebase-tools diffs field overrides by field path, not by scope, so it
# will NOT repair an *existing* override whose queryScope drifted (e.g. a field
# that is COLLECTION but should be COLLECTION_GROUP) — it logs "Skipping existing
# field override" and leaves it. On a fresh database it creates them all
# correctly. To force a scope change on an existing override, PATCH it directly:
#   curl -X PATCH \
#     "https://firestore.googleapis.com/v1/projects/<P>/databases/<DB>/collectionGroups/<CG>/fields/<FIELD>?updateMask=indexConfig" \
#     -H "Authorization: Bearer $(gcloud auth print-access-token)" \
#     -H "Content-Type: application/json" \
#     -d '{"indexConfig":{"indexes":[{"queryScope":"COLLECTION_GROUP","fields":[{"fieldPath":"<FIELD>","order":"ASCENDING"}]}]}}'
#
# Usage:
#   infra/scripts/deploy-firestore-indexes.sh                  # target: production (prompts before deleting)
#   infra/scripts/deploy-firestore-indexes.sh --database='(default)'
#   infra/scripts/deploy-firestore-indexes.sh --force          # no prompts (for CI)
#   infra/scripts/deploy-firestore-indexes.sh --dry-run        # show current indexes, deploy nothing
set -euo pipefail

PROJECT_ID="health-fitness-160"
DATABASE="production"
FORCE=0
DRY_RUN=0

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIRESTORE_DIR="${SCRIPT_DIR}/../firestore"
INDEXES_FILE="${FIRESTORE_DIR}/firestore.indexes.json"

for arg in "$@"; do
  case "$arg" in
    --database=*) DATABASE="${arg#*=}" ;;
    --project=*)  PROJECT_ID="${arg#*=}" ;;
    --force|-f)   FORCE=1 ;;
    --dry-run)    DRY_RUN=1 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

if ! command -v firebase >/dev/null 2>&1; then
  echo "firebase CLI not found. Install it: npm i -g firebase-tools" >&2
  exit 1
fi
if [[ ! -f "$INDEXES_FILE" ]]; then
  echo "Indexes file not found: $INDEXES_FILE" >&2
  exit 1
fi

echo "==> Firestore index deploy"
echo "    project:  ${PROJECT_ID}"
echo "    database: ${DATABASE}"
echo "    source:   ${INDEXES_FILE}"
echo

if [[ "$DRY_RUN" -eq 1 ]]; then
  echo "==> [dry-run] current composite indexes on '${DATABASE}':"
  gcloud firestore indexes composite list \
    --project="$PROJECT_ID" --database="$DATABASE" \
    --format="table(name.basename(), queryScope)" 2>&1 | head -40 || true
  echo
  echo "[dry-run] would run: firebase deploy --only firestore:indexes (database=${DATABASE})"
  exit 0
fi

# firebase resolves the "indexes" path relative to the config file's directory,
# so we write a throwaway config alongside the JSON (cleaned up on exit). The
# array form binds this firestore config to the named database.
TMP_CONFIG="$(mktemp "${FIRESTORE_DIR}/.firebase-deploy.XXXXXX.json")"
trap 'rm -f "$TMP_CONFIG"' EXIT
cat >"$TMP_CONFIG" <<JSON
{
  "firestore": [
    { "database": "${DATABASE}", "indexes": "firestore.indexes.json" }
  ]
}
JSON

# Target the named database via `firestore:<database>`, NOT `firestore:indexes`.
# With the named-database array config form, firebase-tools (>=15) parses the
# `:<x>` suffix of --only as a *database target name*, so `--only firestore:indexes`
# looks for a database literally named "indexes", finds none, and silently
# deploys nothing — then crashes in deployIndexes with
# "Cannot read properties of undefined (reading 'map')". `firestore:<database>`
# selects this database's config; since TMP_CONFIG declares only `indexes` (no
# `rules`), only indexes are deployed.
deploy_args=(deploy --only "firestore:${DATABASE}" --project "$PROJECT_ID" --config "$TMP_CONFIG")
[[ "$FORCE" -eq 1 ]] && deploy_args+=(--force)

echo "==> Running: firebase ${deploy_args[*]}"
firebase "${deploy_args[@]}"

echo
echo "Done. Composite indexes build asynchronously (CREATING -> READY). Check with:"
echo "  gcloud firestore indexes composite list --project=${PROJECT_ID} --database=${DATABASE}"
