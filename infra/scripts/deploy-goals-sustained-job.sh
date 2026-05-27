#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Cloud Run Job that runs the daily SUSTAINED Step
# re-evaluation pass for IMPL-12 Goals.
#
# Re-uses the same Docker image as the long-running backend service
# (`backend:latest` in Artifact Registry). The job activates Spring
# profile `job-sustained`, which triggers the `ReevaluateSustainedJob`
# CommandLineRunner; the runner returns normally, Spring shuts the
# context down, and the JVM exits with 0.
#
# Idempotent: `gcloud run jobs deploy` upserts. Re-run any time to roll a
# fresh image tag — the Cloud Build pipeline also updates this job's
# image on each backend deploy (see `backend/cloudbuild.yaml`).
#
# One-time bootstrap order:
#   1) bash infra/scripts/deploy-goals-sustained-job.sh   (this script)
#   2) bash infra/scripts/bootstrap-goals-scheduler.sh
#
# Ad-hoc execution after deploy:
#   gcloud run jobs execute goals-sustained-reeval --region us-central1 --wait

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="goals-sustained-reeval"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

# Mirror the --set-secrets bindings from backend/cloudbuild.yaml so the
# job's env matches the service exactly. If you change either side, keep
# them in sync.
SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest"

# Mirror the deployed service env (minus PORT/CORS — the job has no HTTP
# surface). SPRING_PROFILES_ACTIVE=job-sustained is the load-bearing flag
# that makes ReevaluateSustainedJob's @Profile activate.
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=production@SPRING_PROFILES_ACTIVE=job-sustained"

echo "==> Deploying Cloud Run Job ${JOB_NAME} (image=${IMAGE})"
gcloud run jobs deploy "${JOB_NAME}" \
  --image="${IMAGE}" \
  --region="${REGION}" \
  --service-account="${RUNTIME_SA}" \
  --set-env-vars="${ENV_VARS}" \
  --set-secrets="${SECRETS}" \
  --max-retries=1 \
  --task-timeout=600 \
  --project="${PROJECT_ID}"

cat <<MSG

Deployed Cloud Run Job ${JOB_NAME}.

To run on demand:
  gcloud run jobs execute ${JOB_NAME} --region ${REGION} --wait

To view recent executions:
  gcloud run jobs executions list --job=${JOB_NAME} --region=${REGION}

Next: register the daily scheduler entry with:
  bash infra/scripts/bootstrap-goals-scheduler.sh

MSG
