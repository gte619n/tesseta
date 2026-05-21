#!/usr/bin/env bash
set -euo pipefail

# Idempotent: generates the Android release keystore + a shared debug keystore,
# stores the release artifacts in Secret Manager, and prints SHA-1 fingerprints
# needed to register the Android OAuth clients in GCP Console.

PROJECT_ID="health-fitness-160"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RELEASE_KEYSTORE="${REPO_ROOT}/health-fitness-release.keystore"
DEBUG_KEYSTORE="${REPO_ROOT}/android/debug.keystore"
RELEASE_ALIAS="upload"
DEBUG_ALIAS="androiddebugkey"

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }
}
require keytool
require gcloud
require openssl

read_password() {
  local prompt="$1"
  local pw1 pw2
  while true; do
    read -r -s -p "$prompt: " pw1; echo
    read -r -s -p "Confirm: " pw2; echo
    if [[ "$pw1" == "$pw2" && -n "$pw1" ]]; then
      printf '%s' "$pw1"
      return 0
    fi
    echo "    passwords do not match or empty, try again" >&2
  done
}

secret_create_or_add() {
  local name="$1"
  local file="$2"
  if gcloud secrets describe "$name" --project="$PROJECT_ID" &>/dev/null; then
    gcloud secrets versions add "$name" \
      --data-file="$file" --project="$PROJECT_ID" >/dev/null
    echo "    added new version to $name"
  else
    gcloud secrets create "$name" \
      --replication-policy=automatic --data-file="$file" --project="$PROJECT_ID" >/dev/null
    echo "    created $name"
  fi
}

print_sha1() {
  local keystore="$1"
  local alias="$2"
  local pw="$3"
  keytool -list -v \
    -keystore "$keystore" \
    -alias "$alias" \
    -storepass "$pw" 2>/dev/null \
    | awk '/SHA1:/ { print $2 }'
}

# --- Debug keystore (checked into the repo by design) ---

echo "==> Shared debug keystore"
if [[ -f "$DEBUG_KEYSTORE" ]]; then
  echo "    exists, skipping"
else
  keytool -genkeypair -v \
    -keystore "$DEBUG_KEYSTORE" \
    -storepass android \
    -keypass android \
    -alias "$DEBUG_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 36500 \
    -storetype PKCS12 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null
  echo "    created $DEBUG_KEYSTORE"
fi

# --- Release keystore (NOT checked in; lives in Secret Manager) ---

echo "==> Release keystore"
if [[ -f "$RELEASE_KEYSTORE" ]]; then
  echo "    local file exists at $RELEASE_KEYSTORE, skipping generation"
  read -r -s -p "Enter release store password (to print fingerprint): " RELEASE_STORE_PW; echo
else
  echo "    no local release keystore found; generating"
  RELEASE_STORE_PW="$(read_password 'Choose a release store password (save it somewhere safe)')"
  RELEASE_KEY_PW="$(read_password 'Choose a release key password (can be the same)')"
  read -r -p "Common Name (CN) for the cert [Evan Ruff]: " CN
  CN="${CN:-Evan Ruff}"
  keytool -genkeypair -v \
    -keystore "$RELEASE_KEYSTORE" \
    -storepass "$RELEASE_STORE_PW" \
    -keypass "$RELEASE_KEY_PW" \
    -alias "$RELEASE_ALIAS" \
    -keyalg RSA -keysize 4096 -validity 36500 \
    -storetype PKCS12 \
    -dname "CN=${CN},O=health-fitness-160,C=US" >/dev/null
  echo "    created $RELEASE_KEYSTORE"

  echo "==> Uploading release keystore + passwords to Secret Manager"
  secret_create_or_add "android-release-keystore" "$RELEASE_KEYSTORE"
  echo -n "$RELEASE_STORE_PW" | secret_create_or_add "android-release-keystore-password" /dev/stdin
  echo -n "$RELEASE_KEY_PW"   | secret_create_or_add "android-release-key-password"      /dev/stdin
fi

# --- Fingerprints ---

echo
echo "==> SHA-1 fingerprints"
DEBUG_SHA1="$(print_sha1 "$DEBUG_KEYSTORE" "$DEBUG_ALIAS" "android")"
RELEASE_SHA1="$(print_sha1 "$RELEASE_KEYSTORE" "$RELEASE_ALIAS" "$RELEASE_STORE_PW")"

cat <<EOF

  Debug   (shared, checked into repo): $DEBUG_SHA1
  Release (Secret Manager):            $RELEASE_SHA1

==> Next steps (Cloud Console, no gcloud equivalent for OAuth client creation):

  1. Visit
       https://console.cloud.google.com/apis/credentials?project=${PROJECT_ID}
     and create three OAuth 2.0 Client IDs:

     a) Web Application — name "health-fitness-web"
        Authorized redirect URIs:
          https://<web-cloud-run-host>/api/auth/callback/google
          http://localhost:3000/api/auth/callback/google

     b) Android — name "health-fitness-android-phone"
        Package:           com.gte619n.healthfitness
        SHA-1 (debug):     $DEBUG_SHA1
        SHA-1 (release):   $RELEASE_SHA1

     c) Android — name "health-fitness-android-wear"
        Package:           com.gte619n.healthfitness
        SHA-1 (debug):     $DEBUG_SHA1
        SHA-1 (release):   $RELEASE_SHA1

  2. Store each client ID in Secret Manager:

     gcloud secrets create oauth-web-client-id            --replication-policy=automatic --data-file=- <<< 'PASTE_HERE'
     gcloud secrets create oauth-web-client-secret        --replication-policy=automatic --data-file=- <<< 'PASTE_HERE'
     gcloud secrets create oauth-android-phone-client-id  --replication-policy=automatic --data-file=- <<< 'PASTE_HERE'
     gcloud secrets create oauth-android-wear-client-id   --replication-policy=automatic --data-file=- <<< 'PASTE_HERE'
     gcloud secrets create oauth-allowed-audiences        --replication-policy=automatic --data-file=- <<< 'web-id,phone-id,wear-id'
     gcloud secrets create authjs-secret                  --replication-policy=automatic --data-file=- < <(openssl rand -base64 32)

  3. Grant the runtime SA secret-accessor on the new secrets:

     for s in oauth-allowed-audiences oauth-web-client-id oauth-web-client-secret authjs-secret; do
       gcloud secrets add-iam-policy-binding "\$s" \\
         --member="serviceAccount:health-fitness-runtime@${PROJECT_ID}.iam.gserviceaccount.com" \\
         --role=roles/secretmanager.secretAccessor --project=${PROJECT_ID} --quiet >/dev/null
     done

EOF
