#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Cloud Run Job that backfills exercise demo media
# (START/MID/END stills) for catalog exercises still at mediaStatus=NONE, for
# IMPL-15. See docs/decisions/ADR-0008-workout-history-import.md.
#
# Re-uses the backend image. The job activates Spring profile
# `job-exercise-media-backfill`, triggering `BackfillExerciseMediaJob`, which
# generates demo media via gemini-3.1-flash-image-preview. Output lands
# NEEDS_REVIEW (the anatomical gate); approve it in /admin/exercises/review.
#
# Bounded per run via APP_EXERCISES_MEDIA_BACKFILL_LIMIT (default 50) so we don't
# fire hundreds of image calls at once. Re-run to converge (picks up remaining
# NONE exercises each time).
#
# Example:
#   APP_EXERCISES_MEDIA_BACKFILL_LIMIT=50 bash infra/scripts/deploy-exercise-media-job.sh

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="backfill-exercise-media"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

LIMIT="${APP_EXERCISES_MEDIA_BACKFILL_LIMIT:-50}"

SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest"

# SPRING_PROFILES_ACTIVE=job-exercise-media-backfill activates the job.
# APP_EXERCISES_MEDIA_ENABLED=true keeps the live Gemini media bean wired (the
# service web app may disable it per env); the limit caps the batch size.
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=production@SPRING_PROFILES_ACTIVE=job-exercise-media-backfill@APP_EXERCISES_MEDIA_ENABLED=true@APP_EXERCISES_MEDIA_BACKFILL_LIMIT=${LIMIT}"

echo "==> Deploying Cloud Run Job ${JOB_NAME} (image=${IMAGE}, limit=${LIMIT})"
gcloud run jobs deploy "${JOB_NAME}" \
  --image="${IMAGE}" \
  --region="${REGION}" \
  --service-account="${RUNTIME_SA}" \
  --set-env-vars="${ENV_VARS}" \
  --set-secrets="${SECRETS}" \
  --max-retries=1 \
  --task-timeout=3600 \
  --project="${PROJECT_ID}"

cat <<MSG

Deployed Cloud Run Job ${JOB_NAME}.

To run on demand (repeat until no NONE exercises remain):
  gcloud run jobs execute ${JOB_NAME} --region ${REGION} --wait

Generated media lands NEEDS_REVIEW — approve in /admin/exercises/review.

MSG
