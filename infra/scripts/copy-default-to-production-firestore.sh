#!/usr/bin/env bash
# Copy the `(default)` Firestore database to the `production` Firestore
# database via export/import through a GCS bucket.
#
# Import is a merge: docs in `(default)` overwrite same-path docs in
# `production`; docs present only in `production` are left alone.
#
# Usage:
#   infra/scripts/copy-default-to-production-firestore.sh           # prompts for confirmation
#   infra/scripts/copy-default-to-production-firestore.sh --yes     # skip prompt
set -euo pipefail

PROJECT_ID="health-fitness-160"
REGION="us-central1"
SRC_DB="(default)"
DST_DB="production"
BUCKET="health-fitness-160-firestore-exports"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
EXPORT_URI="gs://${BUCKET}/default-to-production/${TIMESTAMP}"

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
if ! gcloud firestore databases describe \
    --database="$DST_DB" \
    --project="$PROJECT_ID" >/dev/null 2>&1; then
  echo "    '${DST_DB}' does not exist. Create it first:" >&2
  echo "    gcloud firestore databases create --database=${DST_DB} \\" >&2
  echo "      --location=${REGION} --type=firestore-native --project=${PROJECT_ID}" >&2
  exit 1
fi

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
  destination:  ${DST_DB}
  staging URI:  ${EXPORT_URI}

Import is a merge: same-path docs in '${DST_DB}' will be OVERWRITTEN.
Docs that only exist in '${DST_DB}' will be left alone.

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
