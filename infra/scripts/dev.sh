#!/usr/bin/env bash
set -euo pipefail

# Pulls the OAuth + Auth.js secrets out of Secret Manager and runs the backend
# and web app side-by-side for local end-to-end auth testing.
#
# Backend listens on http://localhost:8080 with CORS allowing localhost:3000.
# Web listens on http://localhost:3000 and forwards bearer tokens to backend.
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
AUTH_GOOGLE_ID="$(secret oauth-web-client-id)"
AUTH_GOOGLE_SECRET="$(secret oauth-web-client-secret)"
AUTH_SECRET="$(secret authjs-secret)"

# --- Backend env ---
export OAUTH_ALLOWED_AUDIENCES
export CORS_ALLOWED_ORIGINS="http://localhost:3000"

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
