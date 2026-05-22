#!/usr/bin/env bash
set -euo pipefail

# Pulls the OAuth + Auth.js secrets out of Secret Manager and runs the backend
# and web app side-by-side for local end-to-end auth testing.
#
# Backend listens on http://localhost:8080 with CORS allowing localhost:3000.
# Web listens on http://localhost:3000 and forwards bearer tokens to backend.
# Both hit real GCP services — Firestore (default) database, KMS via ADC,
# Secret Manager. Run `gcloud auth application-default login` once before
# the first invocation so KMS calls succeed.
# Ctrl-C tears both down.

PROJECT_ID="health-fitness-160"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }
}
require gcloud
require pnpm

secret() {
  gcloud secrets versions access latest --secret="$1" --project="$PROJECT_ID"
}

echo "==> Fetching secrets from Secret Manager"
OAUTH_ALLOWED_AUDIENCES="$(secret oauth-allowed-audiences)"
OAUTH_WEB_CLIENT_ID="$(secret oauth-web-client-id)"
OAUTH_WEB_CLIENT_SECRET="$(secret oauth-web-client-secret)"
AUTH_GOOGLE_ID="$OAUTH_WEB_CLIENT_ID"
AUTH_GOOGLE_SECRET="$OAUTH_WEB_CLIENT_SECRET"
AUTH_SECRET="$(secret authjs-secret)"
GOOGLE_HEALTH_WEBHOOK_SECRET="$(secret google-health-webhook-secret)"
GEMINI_API_KEY="$(secret gemini_api_key)"

# --- Backend env ---
# IMPL-02 audience checks + CORS allow-list for the local web origin.
export OAUTH_ALLOWED_AUDIENCES
export CORS_ALLOWED_ORIGINS="http://localhost:3000"
# IMPL-03 Firestore project. Local dev intentionally hits the (default)
# database — keeps experimental data out of production. The deployed
# Cloud Run service overrides via FIRESTORE_DATABASE_ID=production in
# backend/cloudbuild.yaml.
export GCP_PROJECT_ID="$PROJECT_ID"
# IMPL-04 Google Health: backend refreshes the per-user OAuth token via
# the web OAuth client, encrypts refresh tokens via the live KMS key
# (defaulted in application.yml), and validates webhook callbacks with
# the shared secret.
export OAUTH_WEB_CLIENT_ID
export OAUTH_WEB_CLIENT_SECRET
export GOOGLE_HEALTH_WEBHOOK_SECRET
# DEXA: PDFs go to GCS; Gemini API extracts the structured data.
export GEMINI_API_KEY

# --- Web env (.env.local) ---
WEB_ENV="${REPO_ROOT}/web/.env.local"
cat > "$WEB_ENV" <<EOF
AUTH_SECRET=${AUTH_SECRET}
AUTH_GOOGLE_ID=${AUTH_GOOGLE_ID}
AUTH_GOOGLE_SECRET=${AUTH_GOOGLE_SECRET}
BACKEND_URL=http://localhost:8080
EOF
echo "    wrote $WEB_ENV"

# --- Run both ---

cleanup() {
  echo
  echo "==> Stopping"
  [[ -n "${BACKEND_PID:-}" ]] && kill "$BACKEND_PID" 2>/dev/null || true
  [[ -n "${WEB_PID:-}"     ]] && kill "$WEB_PID"     2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "==> Starting backend"
(cd "${REPO_ROOT}/backend" && ./gradlew :app:bootRun --console=plain) &
BACKEND_PID=$!

echo "==> Starting web"
(cd "${REPO_ROOT}/web" && pnpm dev) &
WEB_PID=$!

echo
echo "  Backend: http://localhost:8080  (PID $BACKEND_PID)"
echo "  Web:     http://localhost:3000  (PID $WEB_PID)"
echo "  Try:     open http://localhost:3000/me  in an incognito window"
echo
echo "  Ctrl-C to stop both."

wait
