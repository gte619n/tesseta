#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Cloud Run Job that periodically re-pulls each
# connected user's recent Withings data (sleep + weight/body-fat) — the safety
# net for missed notify callbacks or a lapsed subscription. It also exercises
# each (rotating) refresh token, so a dead connection is caught and the user
# gets a reconnect push (same idea as gh-refresh).
#
# Re-uses the same Docker image as the long-running backend service
# (`backend:latest` in Artifact Registry). The job activates Spring profile
# `job-withings-refresh`, which triggers the `WithingsRefreshJob`
# CommandLineRunner; the runner returns normally, Spring shuts the context
# down, and the JVM exits with 0.
#
# Idempotent: `gcloud run jobs deploy` upserts. The Cloud Build pipeline also
# updates this job's image on each backend deploy (see `backend/cloudbuild.yaml`).
#
# One-time bootstrap order:
#   1) bash infra/scripts/deploy-withings-refresh-job.sh   (this script)
#   2) bash infra/scripts/bootstrap-withings-refresh-scheduler.sh
#
# Prerequisite secrets (create once — see infra/README.md):
#   withings-client-id, withings-client-secret
#
# Ad-hoc execution after deploy:
#   gcloud run jobs execute withings-refresh --region us-central1 --wait

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="withings-refresh"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

# Mirror the --set-secrets bindings from backend/cloudbuild.yaml so the job's
# context boots identically, plus the Withings client id/secret the refresh-token
# exchange needs. Keep in sync with the service if either side changes.
SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest,WITHINGS_CLIENT_ID=withings-client-id:latest,WITHINGS_CLIENT_SECRET=withings-client-secret:latest"

# Mirror the deployed service env (minus PORT/CORS — the job has no HTTP
# surface). SPRING_PROFILES_ACTIVE=job-withings-refresh is the load-bearing flag
# that makes WithingsRefreshJob's @Profile activate. GOOGLE_HEALTH_KMS_KEY is the
# envelope-encryption key (Withings refresh tokens share it). APP_FCM_ENABLED so
# the reconnect push (for a token that died) delivers.
# PLATFORM_ALLOW_EPHEMERAL_KEY=true: boots the full Spring context but never mints
# platform OAuth tokens, so it doesn't need the stable RS256 signing key.
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=production@APP_FCM_ENABLED=true@PLATFORM_ALLOW_EPHEMERAL_KEY=true@SPRING_PROFILES_ACTIVE=job-withings-refresh"

echo "==> Deploying Cloud Run Job ${JOB_NAME} (image=${IMAGE})"
gcloud run jobs deploy "${JOB_NAME}" \
  --image="${IMAGE}" \
  --region="${REGION}" \
  --service-account="${RUNTIME_SA}" \
  --set-env-vars="${ENV_VARS}" \
  --set-secrets="${SECRETS}" \
  --max-retries=1 \
  --task-timeout=900 \
  --project="${PROJECT_ID}"

cat <<MSG

Deployed Cloud Run Job ${JOB_NAME}.

To run on demand:
  gcloud run jobs execute ${JOB_NAME} --region ${REGION} --wait

To view recent executions:
  gcloud run jobs executions list --job=${JOB_NAME} --region=${REGION}

Next: register the scheduler entry with:
  bash infra/scripts/bootstrap-withings-refresh-scheduler.sh

MSG
