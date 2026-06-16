#!/usr/bin/env bash
# Mirror the `production` Firestore database DOWN into the `(default)`
# Firestore database via export/import through a GCS bucket.
#
# This is the reverse of copy-default-to-production-firestore.sh — use it to
# pull a copy of production data into the database that local dev reads
# (`dev.sh` runs the backend against `(default)`; see infra/scripts/dev.sh).
#
# Import is a MERGE: docs from `production` overwrite same-path docs in
# `(default)`; docs that exist ONLY in `(default)` (your local/experimental
# data) are left in place. It is not a wipe-and-replace — to get a pristine
# mirror, delete and recreate the `(default)` database first.
#
# Usage:
#   infra/scripts/copy-production-to-default-firestore.sh           # prompts for confirmation
#   infra/scripts/copy-production-to-default-firestore.sh --yes     # skip prompt
set -euo pipefail

PROJECT_ID="health-fitness-160"
REGION="us-central1"
SRC_DB="production"
DST_DB="(default)"
BUCKET="health-fitness-160-firestore-exports"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
EXPORT_URI="gs://${BUCKET}/production-to-default/${TIMESTAMP}"

AUTO_YES=0
for arg in "$@"; do
  case "$arg" in
    --yes|-y) AUTO_YES=1 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

echo "==> Verify source database '${SRC_DB}' exists"
gcloud firestore databases describe \
  --database="$SRC_DB" \
  --project="$PROJECT_ID" >/dev/null

echo "==> Verify destination database '${DST_DB}' exists"
gcloud firestore databases describe \
  --database="$DST_DB" \
  --project="$PROJECT_ID" >/dev/null

echo "==> Ensure export bucket gs://${BUCKET}"
if ! gcloud storage buckets describe "gs://${BUCKET}" \
    --project="$PROJECT_ID" >/dev/null 2>&1; then
  gcloud storage buckets create "gs://${BUCKET}" \
    --project="$PROJECT_ID" \
    --location="$REGION" \
    --uniform-bucket-level-access
else
  echo "    exists, skipping"
fi

echo "==> Grant Firestore service agent access to the bucket"
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
FIRESTORE_SA="service-${PROJECT_NUMBER}@gcp-sa-firestore.iam.gserviceaccount.com"
for role in roles/storage.objectCreator roles/storage.objectViewer; do
  gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
    --member="serviceAccount:${FIRESTORE_SA}" \
    --role="$role" \
    --project="$PROJECT_ID" >/dev/null
  echo "    ${role}"
done

cat <<SUMMARY

About to copy Firestore data:
  project:      ${PROJECT_ID}
  source DB:    ${SRC_DB}
  destination:  ${DST_DB}   <-- the database local dev reads
  staging URI:  ${EXPORT_URI}

Import is a merge: same-path docs in '${DST_DB}' will be OVERWRITTEN with
production data. Docs that only exist in '${DST_DB}' are left alone.

SUMMARY

if [[ "$AUTO_YES" -ne 1 ]]; then
  read -r -p "Proceed? Type 'yes' to continue: " reply
  if [[ "$reply" != "yes" ]]; then
    echo "Aborted." >&2
    exit 1
  fi
fi

echo "==> Export '${SRC_DB}' to ${EXPORT_URI}"
gcloud firestore export "$EXPORT_URI" \
  --database="$SRC_DB" \
  --project="$PROJECT_ID"

echo "==> Import into '${DST_DB}' from ${EXPORT_URI}"
gcloud firestore import "$EXPORT_URI" \
  --database="$DST_DB" \
  --project="$PROJECT_ID"

echo
echo "Done. Export retained at ${EXPORT_URI}"
