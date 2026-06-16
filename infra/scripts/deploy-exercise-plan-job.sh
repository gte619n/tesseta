#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Cloud Run Job that backfills exercise frame PLANS
# (the reviewable demoPlan) for catalog exercises still at planStatus=NONE, for
# IMPL-19. See docs/specs/IMPL-19-dynamic-demo-frames.md.
#
# Re-uses the backend image. The job activates Spring profile
# `job-exercise-plan-backfill`, triggering `BackfillExerciseFramePlanJob`, which
# derives each plan via gemini-3.5-flash (the approved text model), reading the
# exercise plus its public-library reference page. Plans land NEEDS_REVIEW (the
# cheap human plan-review gate that precedes the costlier media generation);
# approve them, then run the media backfill (deploy-exercise-media-job.sh).
#
# Bounded per run via APP_EXERCISES_PLAN_BACKFILL_LIMIT (default 50). Re-run to
# converge (picks up remaining NONE exercises each time).
#
# Example:
#   APP_EXERCISES_PLAN_BACKFILL_LIMIT=50 bash infra/scripts/deploy-exercise-plan-job.sh

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="backfill-exercise-plan"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

LIMIT="${APP_EXERCISES_PLAN_BACKFILL_LIMIT:-50}"

SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest"

# SPRING_PROFILES_ACTIVE=job-exercise-plan-backfill activates the job.
# GEMINI_API_KEY (via secrets) wires the shared genai client the planner needs.
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=production@SPRING_PROFILES_ACTIVE=job-exercise-plan-backfill@APP_EXERCISES_PLAN_BACKFILL_LIMIT=${LIMIT}"

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

Generated plans land NEEDS_REVIEW — review/edit in the admin plan editor and
approve, then run deploy-exercise-media-job.sh to generate the images.

MSG
