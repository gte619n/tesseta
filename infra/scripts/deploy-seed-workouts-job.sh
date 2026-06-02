#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Cloud Run Job that seeds one user's workout history from
# future_workouts.json for IMPL-15 (the name-only exercise catalog, the program
# + 20 phases, and the completed-session history with logged weights). See
# docs/decisions/ADR-0008-workout-history-import.md.
#
# Re-uses the same Docker image as the backend service. The job activates Spring
# profile `job-seed-workouts`, triggering the `SeedWorkoutHistoryJob`
# CommandLineRunner; it returns normally and the JVM exits 0.
#
# Idempotent: catalog writes skip ids that already exist; program/phase/session
# ids are deterministic, so re-running upserts cleanly.
#
# Required env at deploy time:
#   WORKOUTS_SEED_JSON_PATH  path to future_workouts.json (mounted GCS file or
#                            http(s) URL reachable from the job)
#   WORKOUTS_SEED_USER_ID    Firestore user id (Google `sub`) that owns the
#                            seeded program + history. NOT committed anywhere.
#
# Exercise enrichment uses gemini-3.5-flash (GEMINI_API_KEY secret, below).
#
# Example:
#   WORKOUTS_SEED_JSON_PATH=gs-mount/future_workouts.json \
#   WORKOUTS_SEED_USER_ID=1234567890 \
#     bash infra/scripts/deploy-seed-workouts-job.sh

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="seed-workout-history"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

JSON_PATH="${WORKOUTS_SEED_JSON_PATH:-}"
USER_ID="${WORKOUTS_SEED_USER_ID:-}"

if [[ -z "${USER_ID}" ]]; then
  echo "ERROR: WORKOUTS_SEED_USER_ID must be set (the owning user's Google sub)." >&2
  exit 1
fi

# Mirror the --set-secrets bindings from backend/cloudbuild.yaml. GEMINI_API_KEY
# powers the exercise metadata enricher.
SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest"

# SPRING_PROFILES_ACTIVE=job-seed-workouts is the load-bearing flag that makes
# SeedWorkoutHistoryJob's @Profile activate. The two seed env vars feed
# app.workouts.seed.{json-path,user-id}.
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=production@SPRING_PROFILES_ACTIVE=job-seed-workouts@APP_WORKOUTS_SEED_JSON_PATH=${JSON_PATH}@APP_WORKOUTS_SEED_USER_ID=${USER_ID}"

echo "==> Deploying Cloud Run Job ${JOB_NAME} (image=${IMAGE}, user=${USER_ID})"
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

To run on demand:
  gcloud run jobs execute ${JOB_NAME} --region ${REGION} --wait

After seeding, generate demo images with:
  bash infra/scripts/deploy-exercise-media-job.sh
  gcloud run jobs execute backfill-exercise-media --region ${REGION} --wait

MSG
