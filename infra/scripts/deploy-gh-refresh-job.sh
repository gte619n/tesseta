#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Cloud Run Job that periodically re-pulls each
# connected user's recent Google Health data (weight, body-fat, steps,
# sleep, resting-HR, HRV) — the safety net for missed webhooks or a lapsed
# subscription. It also exercises each refresh token, so a dead connection
# is caught and the user gets a reconnect push (same as gh-health-check).
#
# Re-uses the same Docker image as the long-running backend service
# (`backend:latest` in Artifact Registry). The job activates Spring profile
# `job-gh-refresh`, which triggers the `GoogleHealthRefreshJob`
# CommandLineRunner; the runner returns normally, Spring shuts the context
# down, and the JVM exits with 0.
#
# Idempotent: `gcloud run jobs deploy` upserts. Re-run any time to roll a
# fresh image tag — the Cloud Build pipeline also updates this job's image
# on each backend deploy (see `backend/cloudbuild.yaml`).
#
# One-time bootstrap order:
#   1) bash infra/scripts/deploy-gh-refresh-job.sh   (this script)
#   2) bash infra/scripts/bootstrap-gh-refresh-scheduler.sh
#
# Ad-hoc execution after deploy:
#   gcloud run jobs execute gh-refresh --region us-central1 --wait

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="gh-refresh"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

# Mirror the --set-secrets bindings from backend/cloudbuild.yaml so the
# job's env matches the service exactly. The refresh-token exchange needs
# the OAuth web client id/secret; FCM needs no extra secret (ADC). If you
# change either side, keep them in sync.
SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest"

# Mirror the deployed service env (minus PORT/CORS — the job has no HTTP
# surface). SPRING_PROFILES_ACTIVE=job-gh-refresh is the load-bearing flag
# that makes GoogleHealthRefreshJob's @Profile activate.
# app.fcm.enabled=true so the reconnect push (for a token that died) delivers.
# PLATFORM_ALLOW_EPHEMERAL_KEY=true: this job boots the full Spring context but
# never mints platform OAuth tokens, so it doesn't need the stable RS256 signing
# key the web service mounts (PlatformKeys fails closed otherwise).
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=production@APP_FCM_ENABLED=true@PLATFORM_ALLOW_EPHEMERAL_KEY=true@SPRING_PROFILES_ACTIVE=job-gh-refresh"

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
  bash infra/scripts/bootstrap-gh-refresh-scheduler.sh

MSG
