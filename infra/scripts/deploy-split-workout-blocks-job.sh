#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the one-time Cloud Run Job that splits the imported-history
# program's flat "Logged" sessions into Warm-up / Main / Cool-down blocks.
#
# Re-uses the backend image. Activates Spring profile `job-split-blocks`,
# triggering `SplitImportedWorkoutBlocksJob`. Hybrid classification: strength
# moves are placed in Main deterministically (suitableBlockTypes); the remaining
# mobility/stretch moves are labelled warm-up vs cool-down by gemini-3.5-flash.
#
# Scoped to ONE user's imported program. Defaults to a DRY RUN — inspect the job
# logs, then re-run with APP_WORKOUTS_SPLIT_DRY_RUN=false to persist.
#
# Required:
#   APP_WORKOUTS_SPLIT_USER_ID=<firebase uid>
# Example (dry run):
#   APP_WORKOUTS_SPLIT_USER_ID=102934494306972576484 \
#     bash infra/scripts/deploy-split-workout-blocks-job.sh
#   gcloud run jobs execute split-workout-blocks --region us-central1 --wait
# Then to persist:
#   APP_WORKOUTS_SPLIT_USER_ID=102934494306972576484 APP_WORKOUTS_SPLIT_DRY_RUN=false \
#     bash infra/scripts/deploy-split-workout-blocks-job.sh
#   gcloud run jobs execute split-workout-blocks --region us-central1 --wait

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="split-workout-blocks"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

USER_ID="${APP_WORKOUTS_SPLIT_USER_ID:?set APP_WORKOUTS_SPLIT_USER_ID to the target Firebase uid}"
DRY_RUN="${APP_WORKOUTS_SPLIT_DRY_RUN:-true}"
# Which Firestore database to target. Deployed data lives in "production"; the
# (default) database is the local/dev one. Override to run against either.
DB_ID="${FIRESTORE_DATABASE_ID:-production}"

SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest"

# SPRING_PROFILES_ACTIVE=job-split-blocks activates the job. The warm-up/cool-down
# classifier is on by default (app.workouts.split.gemini-enabled defaults true)
# and uses the wired GEMINI_API_KEY.
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=${DB_ID}@SPRING_PROFILES_ACTIVE=job-split-blocks@APP_WORKOUTS_SPLIT_USER_ID=${USER_ID}@APP_WORKOUTS_SPLIT_DRY_RUN=${DRY_RUN}"

echo "==> Deploying Cloud Run Job ${JOB_NAME} (user=${USER_ID}, db=${DB_ID}, dryRun=${DRY_RUN})"
gcloud run jobs deploy "${JOB_NAME}" \
  --image="${IMAGE}" \
  --region="${REGION}" \
  --service-account="${RUNTIME_SA}" \
  --set-env-vars="${ENV_VARS}" \
  --set-secrets="${SECRETS}" \
  --max-retries=1 \
  --task-timeout=1800 \
  --project="${PROJECT_ID}"

cat <<MSG

Deployed Cloud Run Job ${JOB_NAME} (dryRun=${DRY_RUN}).

Run it:
  gcloud run jobs execute ${JOB_NAME} --region ${REGION} --wait

Dry run first (default) — inspect logs for the section summary + sample split,
then redeploy with APP_WORKOUTS_SPLIT_DRY_RUN=false and execute again to persist.

MSG
