#!/usr/bin/env bash
# Imports the three Cloud Build triggers defined in infra/triggers/*.yaml and
# grants the Cloud Build runtime SA the roles each trigger needs.
#
# Idempotent: `gcloud builds triggers import` updates an existing trigger
# matched by name, creates it otherwise.
#
# Prerequisites (see footer message for details if any are missing):
#   1. The Cloud Build GitHub App is installed on gte619n/health-fitness-160.
#   2. Secrets exist: android-release-keystore, android-release-keystore-password,
#      android-release-key-password, oauth-web-client-id, firebase-android-app-id.
#   3. Firebase project is linked to GCP project health-fitness-160 with an
#      Android app registered for package com.gte619n.healthfitness, and a
#      tester group named `internal-testers` exists.
set -euo pipefail

PROJECT_ID="health-fitness-160"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
TRIGGERS_DIR="${REPO_ROOT}/infra/triggers"
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')"
# In new GCP projects, Cloud Build executes builds as the Compute Engine
# default SA (the legacy ${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com was
# deprecated in 2024). We pin this here so trigger steps run as a SA we have
# explicitly granted the roles to below.
CLOUDBUILD_SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

echo "==> Import triggers"
for f in backend.yaml web.yaml android.yaml; do
  echo "    ${f}"
  gcloud builds triggers import \
    --source="${TRIGGERS_DIR}/${f}" \
    --project="$PROJECT_ID" >/dev/null
done

echo "==> Grant Cloud Build SA roles (${CLOUDBUILD_SA})"

# Run admin + Artifact Registry writer: deploy + push images. roles/editor
# covers these on legacy projects but we grant explicitly so this works on
# projects where editor has been pruned.
for role in roles/run.admin roles/artifactregistry.writer; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${CLOUDBUILD_SA}" \
    --role="$role" --condition=None --quiet >/dev/null
  echo "    ${role}"
done

# Secret Manager accessor: every trigger reads from Secret Manager
# (backend reads OAUTH_*, web reads AUTH_*, android reads signing material).
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${CLOUDBUILD_SA}" \
  --role=roles/secretmanager.secretAccessor \
  --condition=None --quiet >/dev/null
echo "    roles/secretmanager.secretAccessor"

# Firebase App Distribution admin: NOT included in roles/editor, must be
# granted explicitly for android.yaml's distribute step.
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${CLOUDBUILD_SA}" \
  --role=roles/firebaseappdistro.admin \
  --condition=None --quiet >/dev/null
echo "    roles/firebaseappdistro.admin"

# Service Account User on the runtime SA: needed so Cloud Run deploys can
# attach health-fitness-runtime@.
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA" \
  --member="serviceAccount:${CLOUDBUILD_SA}" \
  --role=roles/iam.serviceAccountUser \
  --project="$PROJECT_ID" --quiet >/dev/null
echo "    roles/iam.serviceAccountUser on ${RUNTIME_SA}"

echo "==> Android release artifact bucket"
ANDROID_BUCKET="gs://${PROJECT_ID}-android-releases"
if ! gcloud storage buckets describe "$ANDROID_BUCKET" \
    --project="$PROJECT_ID" >/dev/null 2>&1; then
  gcloud storage buckets create "$ANDROID_BUCKET" \
    --project="$PROJECT_ID" \
    --location=us-central1 \
    --uniform-bucket-level-access
else
  echo "    exists, skipping"
fi

cat <<MSG

Triggers imported. Verify with:
  gcloud builds triggers list --project=${PROJECT_ID}

Manual prerequisites — confirm each before pushing to main:

  1. GitHub App installed on the repo
     If the imports above failed with "repository not found", install the
     Cloud Build GitHub App and authorize gte619n/health-fitness-160:
       https://github.com/marketplace/google-cloud-build
     Then re-run this script.

  2. Required secrets exist in Secret Manager
     gcloud secrets list --project=${PROJECT_ID} \\
       --filter='name ~ (android-release|oauth-web-client-id|firebase-android-app-id)'
     If 'firebase-android-app-id' is missing, fetch it from the Firebase
     console (Project settings → General → Your apps → App ID) and store:
       echo -n 'PASTE_APP_ID' | gcloud secrets create firebase-android-app-id \\
         --replication-policy=automatic --data-file=- --project=${PROJECT_ID}

  3. Firebase App Distribution
     - Firebase project is linked to GCP project ${PROJECT_ID}
     - Android app registered for package com.gte619n.healthfitness
     - Tester group named 'internal-testers' exists with at least one tester
       https://console.firebase.google.com/project/${PROJECT_ID}/appdistribution

  4. android/app/build.gradle.kts has a release signing config that reads
     ANDROID_RELEASE_KEYSTORE / ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_PASSWORD
     from env vars. Without that, the android trigger will produce an
     unsigned APK and the distribute step will fail.

MSG
