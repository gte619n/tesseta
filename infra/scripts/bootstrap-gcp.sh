#!/usr/bin/env bash
set -euo pipefail

PROJECT_ID="health-fitness-160"
REGION="us-central1"
PROJECT_NUMBER=$(gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)')
RUNTIME_SA="health-fitness-runtime"
RUNTIME_SA_EMAIL="${RUNTIME_SA}@${PROJECT_ID}.iam.gserviceaccount.com"

echo "==> Artifact Registry"
if ! gcloud artifacts repositories describe health-fitness \
    --location="$REGION" --project="$PROJECT_ID" &>/dev/null; then
  gcloud artifacts repositories create health-fitness \
    --repository-format=docker \
    --location="$REGION" \
    --description="Health Fitness container images" \
    --project="$PROJECT_ID"
else
  echo "    exists, skipping"
fi

echo "==> Runtime service account"
if ! gcloud iam service-accounts describe "$RUNTIME_SA_EMAIL" \
    --project="$PROJECT_ID" &>/dev/null; then
  gcloud iam service-accounts create "$RUNTIME_SA" \
    --display-name="Health Fitness Cloud Run runtime" \
    --project="$PROJECT_ID"
else
  echo "    exists, skipping"
fi

echo "==> Grant runtime SA roles"
for role in \
    roles/datastore.user \
    roles/secretmanager.secretAccessor \
    roles/storage.objectAdmin \
    roles/logging.logWriter \
    roles/monitoring.metricWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID" \
    --member="serviceAccount:${RUNTIME_SA_EMAIL}" \
    --role="$role" \
    --condition=None \
    --quiet >/dev/null
  echo "    $role"
done

echo "==> Grant Cloud Build deploy permissions"
CLOUDBUILD_SA="${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com"
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${CLOUDBUILD_SA}" \
  --role="roles/run.admin" --condition=None --quiet >/dev/null
gcloud projects add-iam-policy-binding "$PROJECT_ID" \
  --member="serviceAccount:${CLOUDBUILD_SA}" \
  --role="roles/artifactregistry.writer" --condition=None --quiet >/dev/null
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA_EMAIL" \
  --member="serviceAccount:${CLOUDBUILD_SA}" \
  --role="roles/iam.serviceAccountUser" \
  --project="$PROJECT_ID" --quiet >/dev/null

echo "==> Application GCS buckets"
# Generated images (food/studio, drug, equipment, gym) are uploaded by the
# runtime SA (needs roles/storage.objectAdmin, granted above) and served as
# public URLs (https://storage.googleapis.com/<bucket>/<object>) — so the
# image buckets get allUsers:objectViewer. The nutrition bucket was the one
# missing in prod that broke food-image generation; create-if-absent here so
# a fresh re-bootstrap provisions it. Private user-document buckets (DEXA PDFs,
# blood-test PDFs) are intentionally NOT made public and are created by their
# own IMPL flows.
NUTRITION_BUCKET="gs://${PROJECT_ID}-nutrition-photos"
if ! gcloud storage buckets describe "$NUTRITION_BUCKET" \
    --project="$PROJECT_ID" &>/dev/null; then
  gcloud storage buckets create "$NUTRITION_BUCKET" \
    --location="$REGION" --uniform-bucket-level-access --project="$PROJECT_ID"
else
  echo "    ${NUTRITION_BUCKET} exists, skipping create"
fi
gcloud storage buckets add-iam-policy-binding "$NUTRITION_BUCKET" \
  --member="allUsers" --role="roles/storage.objectViewer" \
  --project="$PROJECT_ID" --quiet >/dev/null
echo "    ${NUTRITION_BUCKET} public-read"

# Exercise demo media (IMPL-15): generated stills served as public URLs, same
# pattern as nutrition. Was missing in prod and broke exercise media generation
# ("Media Failed") — create-if-absent so a re-bootstrap provisions it.
EXERCISE_MEDIA_BUCKET="gs://${PROJECT_ID}-exercise-media"
if ! gcloud storage buckets describe "$EXERCISE_MEDIA_BUCKET" \
    --project="$PROJECT_ID" &>/dev/null; then
  gcloud storage buckets create "$EXERCISE_MEDIA_BUCKET" \
    --location="$REGION" --uniform-bucket-level-access --project="$PROJECT_ID"
else
  echo "    ${EXERCISE_MEDIA_BUCKET} exists, skipping create"
fi
gcloud storage buckets add-iam-policy-binding "$EXERCISE_MEDIA_BUCKET" \
  --member="allUsers" --role="roles/storage.objectViewer" \
  --project="$PROJECT_ID" --quiet >/dev/null
echo "    ${EXERCISE_MEDIA_BUCKET} public-read"

echo "==> Exercise thumbnail Cloud Function SA (IMPL-20, ADR-0017)"
# The repo's first Cloud Function (functions/exercise-thumbnails, deployed by
# deploy-thumbnail-fn.sh) generates webp thumbnails on GCS finalize. It runs as
# its own SA (least privilege: only objectAdmin on the exercise-media bucket, no
# project-wide storage role like the shared runtime SA) so a thumbnailing bug
# can't touch other buckets.
THUMBNAIL_FN_SA="exercise-thumbnails-fn"
THUMBNAIL_FN_SA_EMAIL="${THUMBNAIL_FN_SA}@${PROJECT_ID}.iam.gserviceaccount.com"
if ! gcloud iam service-accounts describe "$THUMBNAIL_FN_SA_EMAIL" \
    --project="$PROJECT_ID" &>/dev/null; then
  gcloud iam service-accounts create "$THUMBNAIL_FN_SA" \
    --display-name="Exercise thumbnail Cloud Function runtime" \
    --project="$PROJECT_ID"
else
  echo "    exists, skipping"
fi
gcloud storage buckets add-iam-policy-binding "$EXERCISE_MEDIA_BUCKET" \
  --member="serviceAccount:${THUMBNAIL_FN_SA_EMAIL}" \
  --role="roles/storage.objectAdmin" \
  --project="$PROJECT_ID" --quiet >/dev/null
echo "    ${THUMBNAIL_FN_SA_EMAIL} -> objectAdmin on ${EXERCISE_MEDIA_BUCKET}"

echo "==> Firestore default database"
if ! gcloud firestore databases describe --database='(default)' \
    --project="$PROJECT_ID" &>/dev/null; then
  gcloud firestore databases create \
    --location="$REGION" \
    --type=firestore-native \
    --project="$PROJECT_ID"
else
  echo "    exists, skipping"
fi

cat <<MSG

Bootstrap complete.

The following two steps require the Cloud Console (no gcloud equivalent for
standard OAuth client creation as of 2026):

1. OAuth consent screen
   https://console.cloud.google.com/apis/credentials/consent?project=${PROJECT_ID}
   - User type: External
   - Publishing status: Testing
   - Add your own Google account as a test user
   - Scopes: leave empty for now, add Google Health API scopes later

2. OAuth 2.0 Client ID (Web application)
   https://console.cloud.google.com/apis/credentials?project=${PROJECT_ID}
   - Application type: Web application
   - Name: health-fitness-backend
   - Authorized redirect URIs:
       http://localhost:8080/login/oauth2/code/google
   - Save the client ID and client secret

3. Store the client secret:
   echo -n 'PASTE_SECRET_HERE' | gcloud secrets create google-oauth-client-secret \\
     --data-file=- --project=${PROJECT_ID}
   echo -n 'PASTE_CLIENT_ID_HERE' | gcloud secrets create google-oauth-client-id \\
     --data-file=- --project=${PROJECT_ID}

MSG
