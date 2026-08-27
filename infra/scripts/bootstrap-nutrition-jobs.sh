#!/usr/bin/env bash
#
# Bootstrap the Tier 3 durable nutrition-jobs pipeline: a Cloud Tasks queue that
# persists background work (image generation + photo/description analysis) and
# POSTs it back to the backend's internal handler, retrying with backoff until it
# succeeds — so a Cloud Run throttle / scale-in / redeploy mid-flight no longer
# loses the job.
#
# Idempotent: safe to re-run. Run once per project, then flip the backend to
# `NUTRITION_JOBS_MODE=cloud-tasks` (see backend/cloudbuild.yaml) and deploy.
#
# Requires an authenticated gcloud with rights to Cloud Tasks, Secret Manager and
# IAM in the project.
set -euo pipefail

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
QUEUE="${QUEUE:-nutrition-jobs}"
SECRET_NAME="${SECRET_NAME:-nutrition-jobs-secret}"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

# Match app.nutrition.jobs.max-attempts so the handler marks a record FAILED on
# the final delivery. maxConcurrentDispatches is the backpressure knob — the cap
# on how many Gemini image/analysis calls run at once (protects cost + memory).
MAX_ATTEMPTS="${MAX_ATTEMPTS:-5}"
MAX_CONCURRENT="${MAX_CONCURRENT:-10}"
MAX_DISPATCHES_PER_SEC="${MAX_DISPATCHES_PER_SEC:-5}"
MIN_BACKOFF="${MIN_BACKOFF:-5s}"
MAX_BACKOFF="${MAX_BACKOFF:-300s}"

echo "==> Enabling Cloud Tasks API"
gcloud services enable cloudtasks.googleapis.com --project="${PROJECT_ID}"

echo "==> Ensuring Cloud Tasks queue '${QUEUE}' in ${REGION}"
if gcloud tasks queues describe "${QUEUE}" --location="${REGION}" --project="${PROJECT_ID}" &>/dev/null; then
  gcloud tasks queues update "${QUEUE}" --location="${REGION}" --project="${PROJECT_ID}" \
    --max-attempts="${MAX_ATTEMPTS}" \
    --max-concurrent-dispatches="${MAX_CONCURRENT}" \
    --max-dispatches-per-second="${MAX_DISPATCHES_PER_SEC}" \
    --min-backoff="${MIN_BACKOFF}" \
    --max-backoff="${MAX_BACKOFF}"
else
  gcloud tasks queues create "${QUEUE}" --location="${REGION}" --project="${PROJECT_ID}" \
    --max-attempts="${MAX_ATTEMPTS}" \
    --max-concurrent-dispatches="${MAX_CONCURRENT}" \
    --max-dispatches-per-second="${MAX_DISPATCHES_PER_SEC}" \
    --min-backoff="${MIN_BACKOFF}" \
    --max-backoff="${MAX_BACKOFF}"
fi

echo "==> Ensuring shared-secret '${SECRET_NAME}'"
if ! gcloud secrets describe "${SECRET_NAME}" --project="${PROJECT_ID}" &>/dev/null; then
  gcloud secrets create "${SECRET_NAME}" --replication-policy=automatic --project="${PROJECT_ID}"
fi
# Add a version only if the secret has none (don't rotate on every re-run).
if ! gcloud secrets versions list "${SECRET_NAME}" --project="${PROJECT_ID}" --format='value(name)' | grep -q .; then
  openssl rand -hex 32 | gcloud secrets versions add "${SECRET_NAME}" --data-file=- --project="${PROJECT_ID}"
fi

echo "==> Granting the runtime service account access"
# Enqueue tasks onto the queue...
gcloud tasks queues add-iam-policy-binding "${QUEUE}" --location="${REGION}" --project="${PROJECT_ID}" \
  --member="serviceAccount:${RUNTIME_SA}" --role="roles/cloudtasks.enqueuer"
# ...and read the shared secret at startup.
gcloud secrets add-iam-policy-binding "${SECRET_NAME}" --project="${PROJECT_ID}" \
  --member="serviceAccount:${RUNTIME_SA}" --role="roles/secretmanager.secretAccessor"

cat <<EOF

Done. To turn on durable mode, set these on the backend Cloud Run service
(backend/cloudbuild.yaml) and deploy:

  env:     NUTRITION_JOBS_MODE=cloud-tasks
           NUTRITION_JOBS_HANDLER_URL=https://api.tesseta.com/internal/nutrition/jobs
  secret:  NUTRITION_JOBS_SECRET=${SECRET_NAME}:latest

The handler is authenticated by the shared secret above; no Cloud Run IAM
invoker binding is needed. The queue delivers to the handler URL; keep the
queue's --max-attempts (${MAX_ATTEMPTS}) equal to app.nutrition.jobs.max-attempts.
EOF
