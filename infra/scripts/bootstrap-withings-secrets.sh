#!/usr/bin/env bash
set -euo pipefail

# Provision the three Secret Manager secrets the Withings integration needs, and
# grant the runtime + Cloud Build service accounts read access. Run this ONCE
# per project BEFORE the first backend/web/android deploy that references them —
# `gcloud run deploy --set-secrets` (and the android build's secret fetch) fail
# hard if a referenced secret doesn't exist, which would red the deploy.
#
#   withings-client-id      — Withings partner-app client id (also used by web +
#                             android builds). Public, but stored as a secret for
#                             uniform handling.
#   withings-client-secret  — partner-app client secret (backend only).
#   withings-webhook-secret — shared secret appended to the notify callback URL
#                             (?secret=…); the only auth on the signature-less
#                             callback. Auto-generated here.
#
# Provide the real client id/secret via env vars so they land as the first
# version; otherwise a REPLACE_ME placeholder is created so deploys succeed, and
# you rotate in the real values before anyone connects:
#   WITHINGS_CLIENT_ID=... WITHINGS_CLIENT_SECRET=... bash infra/scripts/bootstrap-withings-secrets.sh
#
# Idempotent: existing secrets/versions are left as-is (no rotation on re-run).

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
RUNTIME_SA="health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com"
PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
CLOUDBUILD_SA="${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com"

# ensure_secret <name> <initial-value>: create the secret if missing and add the
# initial version only when it has none (never rotate on re-run), then grant
# secretAccessor to the runtime + Cloud Build SAs.
ensure_secret() {
  local name="$1" value="$2"
  echo "==> Ensuring secret '${name}'"
  if ! gcloud secrets describe "${name}" --project="${PROJECT_ID}" &>/dev/null; then
    gcloud secrets create "${name}" --replication-policy=automatic --project="${PROJECT_ID}"
  fi
  if ! gcloud secrets versions list "${name}" --project="${PROJECT_ID}" --format='value(name)' | grep -q .; then
    printf '%s' "${value}" | gcloud secrets versions add "${name}" --data-file=- --project="${PROJECT_ID}"
  fi
  for sa in "${RUNTIME_SA}" "${CLOUDBUILD_SA}"; do
    gcloud secrets add-iam-policy-binding "${name}" --project="${PROJECT_ID}" \
      --member="serviceAccount:${sa}" --role="roles/secretmanager.secretAccessor" >/dev/null
  done
}

ensure_secret withings-client-id "${WITHINGS_CLIENT_ID:-REPLACE_ME}"
ensure_secret withings-client-secret "${WITHINGS_CLIENT_SECRET:-REPLACE_ME}"
ensure_secret withings-webhook-secret "$(openssl rand -hex 32)"

cat <<MSG

Withings secrets provisioned in ${PROJECT_ID}.

If withings-client-id / withings-client-secret hold the REPLACE_ME placeholder,
add the real values from your Withings partner app before anyone connects:
  printf '%s' '<client-id>'     | gcloud secrets versions add withings-client-id     --data-file=- --project=${PROJECT_ID}
  printf '%s' '<client-secret>' | gcloud secrets versions add withings-client-secret --data-file=- --project=${PROJECT_ID}

Register these redirect URIs on the Withings partner app:
  https://app.tesseta.com/api/withings/callback   (web)
  healthfitness://withings-callback                (android)

And point the notify callback base at:
  https://api.tesseta.com/api/webhooks/withings    (WITHINGS_CALLBACK_URL; backend appends ?secret=)

MSG
