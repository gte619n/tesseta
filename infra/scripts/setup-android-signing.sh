#!/usr/bin/env bash
set -euo pipefail

# Idempotent: generates the Android release keystore + a shared debug keystore,
# stores the release artifacts in Secret Manager, and prints SHA-1 fingerprints
# needed to register the Android OAuth clients in GCP Console.
#
# Safe to re-run: existing keystores are reused, and Secret Manager uploads
# always run (creating either the secret or a new version).

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

# Reads a password twice (with confirmation), assigns it to the variable
# named in $1. Uses an out-variable instead of command substitution because
# capturing stdout swallows password characters into shell newline-stripping
# rules and breaks downstream tools that get the password as an argument.
# All UI (prompts and the trailing newline after each silent read) goes to
# stderr so callers can run this without redirection tricks.
read_password() {
  local outvar="$1" prompt="$2"
  local pw1 pw2
  while true; do
    printf '%s: ' "$prompt" >&2
    IFS= read -r -s pw1
    printf '\n' >&2
    printf 'Confirm: ' >&2
    IFS= read -r -s pw2
    printf '\n' >&2
    if [[ "$pw1" == "$pw2" && -n "$pw1" ]]; then
      printf -v "$outvar" '%s' "$pw1"
      return 0
    fi
    printf '    passwords do not match or empty, try again\n' >&2
  done
}

read_password_once() {
  local outvar="$1" prompt="$2"
  local pw
  printf '%s: ' "$prompt" >&2
  IFS= read -r -s pw
  printf '\n' >&2
  printf -v "$outvar" '%s' "$pw"
}

secret_upload_file() {
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

# Uploads stdin bytes as the secret payload using gcloud's documented `-`
# convention. Wrapper because we want both create-or-add behavior and
# stdin input, which gcloud splits across two flags.
secret_upload_stdin() {
  local name="$1"
  if gcloud secrets describe "$name" --project="$PROJECT_ID" &>/dev/null; then
    gcloud secrets versions add "$name" \
      --data-file=- --project="$PROJECT_ID" >/dev/null
    echo "    added new version to $name"
  else
    gcloud secrets create "$name" \
      --replication-policy=automatic --data-file=- --project="$PROJECT_ID" >/dev/null
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

RELEASE_STORE_PW=""
RELEASE_KEY_PW=""

echo "==> Release keystore"
if [[ -f "$RELEASE_KEYSTORE" ]]; then
  echo "    local file exists at $RELEASE_KEYSTORE, will re-upload to Secret Manager"
  read_password_once RELEASE_STORE_PW "Enter existing release store password"
  read_password_once RELEASE_KEY_PW   "Enter existing release key password (often the same)"
else
  echo "    no local release keystore found; generating"
  read_password RELEASE_STORE_PW "Choose a release store password (save it somewhere safe)"
  read_password RELEASE_KEY_PW   "Choose a release key password (can be the same)"
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
fi

# Verify the password unlocks the keystore before we upload anything.
# A wrong password here would otherwise poison Secret Manager.
if ! print_sha1 "$RELEASE_KEYSTORE" "$RELEASE_ALIAS" "$RELEASE_STORE_PW" >/dev/null; then
  echo "ERROR: could not read $RELEASE_KEYSTORE with the supplied store password." >&2
  echo "       Aborting before any Secret Manager upload." >&2
  exit 1
fi

echo "==> Uploading release keystore + passwords to Secret Manager"
secret_upload_file "android-release-keystore" "$RELEASE_KEYSTORE"
printf '%s' "$RELEASE_STORE_PW" | secret_upload_stdin "android-release-keystore-password"
printf '%s' "$RELEASE_KEY_PW"   | secret_upload_stdin "android-release-key-password"

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
