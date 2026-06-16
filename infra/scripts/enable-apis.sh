#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="health-fitness-160"
APIS=(
  run.googleapis.com
  cloudbuild.googleapis.com
  artifactregistry.googleapis.com
  # Gen2 Cloud Functions (IMPL-20 thumbnail fn, ADR-0017). Gen2 functions run on
  # Cloud Run (already enabled) and are triggered via Eventarc; both APIs plus
  # Cloud Build + Artifact Registry (already above) are required to deploy them.
  cloudfunctions.googleapis.com
  eventarc.googleapis.com
  firestore.googleapis.com
  secretmanager.googleapis.com
  iam.googleapis.com
  iamcredentials.googleapis.com
  health.googleapis.com
  logging.googleapis.com
  monitoring.googleapis.com
)

for api in "${APIS[@]}"; do
  echo "Enabling $api"
  gcloud services enable "$api" --project="$PROJECT_ID"
done
