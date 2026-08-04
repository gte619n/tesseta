#!/usr/bin/env bash
set -euo pipefail

# Register (or update) a Cloud Scheduler entry that triggers the gh-refresh
# Cloud Run Job every 6 hours. Each run re-pulls a trailing window
# (app.googlehealth.refresh-window-days, default 14d) of every connected
# user's Google Health data, so the dashboard stays fresh even when Google's
# push webhooks are missed or the subscription lapses.
#
# Why every 6h: webhooks are the primary, low-latency path; this is only the
# safety net. Four runs a day keeps data current without hammering the API,
# and the 14-day window means a single missed run never leaves a gap.
#
# Auth: Cloud Scheduler hits the Cloud Run Admin API
# (`run.googleapis.com/.../jobs/{name}:run`) signed as the runtime service
# account via OAuth. The Job itself is not publicly reachable — this is the
# only invocation path other than `gcloud run jobs execute`.
#
# Idempotent: `describe` decides between create and update. Re-running this
# script is safe at any time.
#
# Prerequisite: the Cloud Run Job gh-refresh must already exist. Run
# `bash infra/scripts/deploy-gh-refresh-job.sh` first.

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
JOB_NAME="gh-refresh"
SCHEDULER_NAME="gh-refresh-6h"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"
SCHEDULE="0 */6 * * *"
TIME_ZONE="America/New_York"

PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
URI="https://${REGION}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${PROJECT_NUMBER}/jobs/${JOB_NAME}:run"

# Cloud Scheduler fires as the runtime SA against the Cloud Run Admin API's
# jobs:run endpoint. Without run.invoker on the job the trigger returns
# PERMISSION_DENIED (code 7) and silently creates no execution, so grant it
# up front. Idempotent — re-adding an existing binding is a no-op.
echo "==> Granting roles/run.invoker on job ${JOB_NAME} to ${RUNTIME_SA}"
gcloud run jobs add-iam-policy-binding "${JOB_NAME}" \
  --region="${REGION}" \
  --member="serviceAccount:${RUNTIME_SA}" \
  --role="roles/run.invoker" \
  --project="${PROJECT_ID}"

echo "==> Cloud Scheduler entry ${SCHEDULER_NAME}"
echo "    schedule=${SCHEDULE} ${TIME_ZONE}"
echo "    target=${URI}"

if gcloud scheduler jobs describe "${SCHEDULER_NAME}" \
    --location="${REGION}" \
    --project="${PROJECT_ID}" &>/dev/null; then
  echo "    exists — updating"
  gcloud scheduler jobs update http "${SCHEDULER_NAME}" \
    --location="${REGION}" \
    --schedule="${SCHEDULE}" \
    --time-zone="${TIME_ZONE}" \
    --uri="${URI}" \
    --http-method=POST \
    --oauth-service-account-email="${RUNTIME_SA}" \
    --project="${PROJECT_ID}"
else
  echo "    not found — creating"
  gcloud scheduler jobs create http "${SCHEDULER_NAME}" \
    --location="${REGION}" \
    --schedule="${SCHEDULE}" \
    --time-zone="${TIME_ZONE}" \
    --uri="${URI}" \
    --http-method=POST \
    --oauth-service-account-email="${RUNTIME_SA}" \
    --project="${PROJECT_ID}"
fi

cat <<MSG

Scheduler ${SCHEDULER_NAME} registered.

To verify:
  gcloud scheduler jobs list --location=${REGION} --project=${PROJECT_ID}

To fire it manually (outside the schedule):
  gcloud scheduler jobs run ${SCHEDULER_NAME} --location=${REGION} --project=${PROJECT_ID}

MSG
