#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Gen2 Cloud Function that generates webp thumbnails for
# exercise media (IMPL-20). See docs/decisions/ADR-0017-gcs-thumbnail-cloud-function.md.
#
# This is the repo's FIRST Cloud Function. It is triggered by GCS object-finalize
# on the exercise-media bucket: for `exercises/{id}/{name}.{ext}` it writes a
# webp thumbnail to `exercises/{id}/thumb/{name}.webp` (max edge 320px, immutable
# cache headers), skipping anything already under `/thumb/`. Source lives in
# functions/exercise-thumbnails/.
#
# The runtime SA (exercise-thumbnails-fn) and its roles/storage.objectAdmin grant
# on the bucket are created by infra/scripts/bootstrap-gcp.sh. The required APIs
# (cloudfunctions, eventarc, run, artifactregistry, cloudbuild) are enabled by
# infra/scripts/enable-apis.sh.
#
# Idempotent: re-running redeploys the same function in place.
#
# Example:
#   bash infra/scripts/deploy-thumbnail-fn.sh

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
FUNCTION_NAME="exercise-thumbnails"
BUCKET="${PROJECT_ID}-exercise-media"
RUNTIME_SA="exercise-thumbnails-fn"
RUNTIME_SA_EMAIL="${RUNTIME_SA}@${PROJECT_ID}.iam.gserviceaccount.com"

# Repo root, so the script runs from anywhere.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SOURCE_DIR="${SCRIPT_DIR}/../../functions/exercise-thumbnails"

echo "==> Ensuring runtime SA ${RUNTIME_SA_EMAIL} exists"
if ! gcloud iam service-accounts describe "$RUNTIME_SA_EMAIL" \
    --project="$PROJECT_ID" &>/dev/null; then
  gcloud iam service-accounts create "$RUNTIME_SA" \
    --display-name="Exercise thumbnail Cloud Function runtime" \
    --project="$PROJECT_ID"
  # A freshly-created SA is not immediately visible to IAM policy writes on
  # other resources (eventual consistency). Wait until it resolves so the
  # bucket/project bindings below don't fail with "does not exist".
  echo "    waiting for SA to propagate"
  for _ in $(seq 1 30); do
    gcloud iam service-accounts describe "$RUNTIME_SA_EMAIL" \
      --project="$PROJECT_ID" &>/dev/null && break
    sleep 2
  done
else
  echo "    exists, skipping"
fi

# The runtime SA is also the trigger's identity. Eventarc validates the bucket
# during trigger creation (needs storage.buckets.get) and delivers events to
# the function (needs eventarc.eventReceiver). objectAdmin covers reading and
# writing the thumbnail objects but NOT bucket metadata, so legacyBucketReader
# is added for the buckets.get used in trigger validation.
echo "==> Granting bucket roles on gs://${BUCKET} to ${RUNTIME_SA}"
for role in roles/storage.objectAdmin roles/storage.legacyBucketReader; do
  gcloud storage buckets add-iam-policy-binding "gs://${BUCKET}" \
    --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
    --role="$role" \
    --project="$PROJECT_ID" --quiet >/dev/null
  echo "    ${role}"
done

echo "==> Granting roles/eventarc.eventReceiver to ${RUNTIME_SA}"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
  --role="roles/eventarc.eventReceiver" \
  --condition=None --quiet >/dev/null

# Gen2 GCS triggers run through Eventarc, which delivers events via Pub/Sub
# populated by the GCS service agent. Grant that agent pubsub.publisher (a
# one-time, idempotent project-level grant required for storage finalize
# triggers).
echo "==> Ensuring GCS service agent can publish Eventarc events"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
GCS_SERVICE_AGENT="service-${PROJECT_NUMBER}@gs-project-accounts.iam.gserviceaccount.com"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${GCS_SERVICE_AGENT}" \
  --role="roles/pubsub.publisher" \
  --condition=None --quiet >/dev/null

echo "==> Deploying Gen2 Cloud Function ${FUNCTION_NAME} (source=${SOURCE_DIR})"
gcloud functions deploy "${FUNCTION_NAME}" \
  --gen2 \
  --region="${REGION}" \
  --runtime=nodejs20 \
  --source="${SOURCE_DIR}" \
  --entry-point=generateThumbnail \
  --trigger-event-filters="type=google.cloud.storage.object.v1.finalized" \
  --trigger-event-filters="bucket=${BUCKET}" \
  --trigger-location="${REGION}" \
  --service-account="${RUNTIME_SA_EMAIL}" \
  --memory=512Mi \
  --cpu=1 \
  --timeout=120s \
  --max-instances=10 \
  --retry \
  --project="${PROJECT_ID}"

# The Eventarc trigger invokes the Gen2 function's underlying Cloud Run service
# AS the runtime SA. With a custom --service-account, gcloud does not auto-grant
# that SA permission to invoke the service, so every delivery 403s with
# "lacks run.routes.invoke". Grant it explicitly (must run after deploy, once the
# Cloud Run service exists). Idempotent.
echo "==> Granting roles/run.invoker on the Cloud Run service to ${RUNTIME_SA}"
gcloud run services add-iam-policy-binding "${FUNCTION_NAME}" \
  --region="${REGION}" \
  --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
  --role="roles/run.invoker" \
  --project="${PROJECT_ID}" --quiet >/dev/null

cat <<MSG

Deployed Cloud Function ${FUNCTION_NAME}.

It now fires on every finalize in gs://${BUCKET}, writing
exercises/{id}/thumb/{name}.webp for each new frame image. Objects already under
/thumb/ are skipped (loop guard).

Existing images predate the trigger — backfill once by re-finalizing them
(re-upload, copy-in-place, or rewrite metadata). See
functions/exercise-thumbnails/README.md.

MSG
