#!/usr/bin/env bash
set -euo pipefail

# Deploy (or update) the Cloud Run Job that seeds the shared food catalog from
# open data for IMPL-13 Milestone 2 (USDA CC0 generics + Open Food Facts ODbL
# packaged products). See docs/decisions/ADR-0006-open-food-facts-licensing.md.
#
# Re-uses the same Docker image as the long-running backend service
# (`backend:latest` in Artifact Registry). The job activates Spring profile
# `job-seed-foods`, which triggers the `SeedFoodCatalogJob` CommandLineRunner;
# the runner returns normally, Spring shuts the context down, and the JVM
# exits 0.
#
# Idempotent: `gcloud run jobs deploy` upserts; the import itself is idempotent
# (deterministic ids `usda-<fdcId>` / `off-<barcode>` + merge save), so the job
# can be re-run any time to refresh.
#
# Source locations are passed as env vars; unset sources are skipped by the job.
# Point them at GCS-mounted files or public dump URLs (optionally gzip .gz):
#   NUTRITION_SEED_USDA_PATH  flattened USDA FoodData Central CSV
#   NUTRITION_SEED_OFF_PATH   Open Food Facts JSONL dump
#
# Ad-hoc execution after deploy:
#   gcloud run jobs execute seed-food-catalog --region us-central1 --wait

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
REGION="${REGION:-us-central1}"
IMAGE="${IMAGE:-us-central1-docker.pkg.dev/${PROJECT_ID}/health-fitness/backend:latest}"
JOB_NAME="seed-food-catalog"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"

# Optional source paths (local file in the image / mounted volume, or http(s)
# URL). Left empty here so the job is a no-op until you wire real sources;
# override at deploy time, e.g.:
#   NUTRITION_SEED_USDA_PATH=https://example/usda.csv \
#   NUTRITION_SEED_OFF_PATH=https://example/off.jsonl.gz \
#     bash infra/scripts/deploy-seed-foods-job.sh
USDA_PATH="${NUTRITION_SEED_USDA_PATH:-}"
OFF_PATH="${NUTRITION_SEED_OFF_PATH:-}"

# Mirror the --set-secrets bindings from backend/cloudbuild.yaml so the job's
# env matches the service. Keep in sync with deploy-goals-sustained-job.sh.
SECRETS="OAUTH_ALLOWED_AUDIENCES=oauth-allowed-audiences:latest,OAUTH_WEB_CLIENT_ID=oauth-web-client-id:latest,OAUTH_WEB_CLIENT_SECRET=oauth-web-client-secret:latest,GOOGLE_HEALTH_WEBHOOK_SECRET=google-health-webhook-secret:latest,GEMINI_API_KEY=gemini_api_key:latest"

# SPRING_PROFILES_ACTIVE=job-seed-foods is the load-bearing flag that makes
# SeedFoodCatalogJob's @Profile activate. The two seed-path env vars feed
# app.nutrition.seed.{usda,off}-path.
ENV_VARS="^@^GCP_PROJECT_ID=${PROJECT_ID}@GOOGLE_HEALTH_KMS_KEY=projects/${PROJECT_ID}/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens@FIRESTORE_DATABASE_ID=production@SPRING_PROFILES_ACTIVE=job-seed-foods@NUTRITION_SEED_USDA_PATH=${USDA_PATH}@NUTRITION_SEED_OFF_PATH=${OFF_PATH}"

echo "==> Deploying Cloud Run Job ${JOB_NAME} (image=${IMAGE})"
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

To view recent executions:
  gcloud run jobs executions list --job=${JOB_NAME} --region=${REGION}

Set NUTRITION_SEED_USDA_PATH / NUTRITION_SEED_OFF_PATH (file or http(s) URL,
optionally .gz) before/at deploy to point the job at real dumps.

MSG
