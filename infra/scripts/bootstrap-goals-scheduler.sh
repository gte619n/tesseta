#!/usr/bin/env bash
set -euo pipefail

# Register (or update) a Cloud Scheduler entry that triggers the
# goals-sustained-reeval Cloud Run Job every day at 03:00 America/New_York.
#
# Why 03:00 ET: late enough that the previous day's Google Health writes
# (steps, vitals, sleep) have landed in Firestore, early enough that the
# morning UI shows current state.
#
# Auth: Cloud Scheduler hits the Cloud Run Admin API
# (`run.googleapis.com/.../jobs/{name}:run`) signed as the runtime
# service account via OAuth. The Job itself is not publicly reachable —
# this is the only invocation path other than `gcloud run jobs execute`.
#
# Idempotent: `create` is attempted first; if the entry already exists,
# `update` is invoked with the same arguments. Re-running this script is
# safe at any time.
#
# Prerequisite: the Cloud Run Job goals-sustained-reeval must already
# exist. Run `bash infra/scripts/deploy-goals-sustained-job.sh` first.

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
JOB_NAME="goals-sustained-reeval"
SCHEDULER_NAME="goals-sustained-reeval-daily"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"
SCHEDULE="0 3 * * *"
TIME_ZONE="America/New_York"

PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
URI="https://${REGION}-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${PROJECT_NUMBER}/jobs/${JOB_NAME}:run"

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
