#!/usr/bin/env bash
set -euo pipefail

# UAT end-to-end runner (sibling of dev.sh, but credential-free).
#
# This is a MANUAL, on-demand suite — it is intentionally NOT wired into CI.
# Boots the Firestore emulator, the backend (dev-login enabled, AI stubbed, no
# GCP creds), and the web app (UAT_AUTH_ENABLED), then runs the Selenium suite
# in uat/. Everything is localhost with fixed local constants — NO `gcloud
# secrets`, NO Google sign-in, NO real Gemini call — so it runs fully offline.
#
# Usage:
#   bash infra/scripts/uat.sh           # boot stack, run uat/ tests, tear down
#   bash infra/scripts/uat.sh --serve   # boot stack and leave it running (Ctrl-C to stop)
#
# Env overrides: BACKEND_PORT (8080), WEB_PORT (3000), EMU_PORT (8081),
# UAT_PROJECT (demo-uat), UAT_HEADLESS (true).

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_PORT="${BACKEND_PORT:-8080}"
WEB_PORT="${WEB_PORT:-3000}"
EMU_PORT="${EMU_PORT:-8081}"
EMU_HOST="127.0.0.1:${EMU_PORT}"
UAT_PROJECT="${UAT_PROJECT:-demo-uat}"
UAT_HEADLESS="${UAT_HEADLESS:-true}"
SERVE=false
[[ "${1:-}" == "--serve" ]] && SERVE=true

# Fixed local-only secrets (safe to commit — they unlock nothing but localhost).
UAT_SESSION_SIGNING_KEY="uat-session-signing-key-0123456789-abcdef"
UAT_AUTH_SECRET="uat-fixed-authjs-secret-for-local-e2e-0123456789"

require() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }; }
require firebase
require java
require pnpm
require curl

LOG_DIR="$(mktemp -d)"
echo "==> logs in $LOG_DIR"

# pnpm/next and gradle/bootRun fork children that outlive a kill of just the
# launcher PID, so kill the whole process subtree (leaves first).
kill_tree() {
  local pid="$1"
  [[ -z "$pid" ]] && return 0
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}

cleanup() {
  echo; echo "==> Stopping"
  kill_tree "${WEB_PID:-}"
  kill_tree "${BACKEND_PID:-}"
  kill_tree "${EMU_PID:-}"
  # Backstop: free our ports in case a grandchild re-parented to init.
  for p in "$WEB_PORT" "$BACKEND_PORT" "$EMU_PORT"; do
    lsof -tiTCP:"$p" -sTCP:LISTEN 2>/dev/null | xargs -r kill 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

wait_for() { # url label tries
  local url="$1" label="$2" tries="${3:-60}"
  for ((i=0; i<tries; i++)); do curl -sf "$url" >/dev/null 2>&1 && { echo "    $label ready"; return 0; }; sleep 2; done
  echo "    $label did NOT come up — see $LOG_DIR" >&2; return 1
}

# --- 1. Firestore emulator ---
echo "==> Starting Firestore emulator on :$EMU_PORT"
( cd "$REPO_ROOT" && firebase emulators:start --only firestore \
    --project "$UAT_PROJECT" --config infra/uat/firebase.json ) > "$LOG_DIR/emulator.log" 2>&1 &
EMU_PID=$!
wait_for "http://${EMU_HOST}/" "emulator" 60 || true  # emulator root 200s once up

# --- 2. Backend (UAT env, credential-free) ---
echo "==> Starting backend on :$BACKEND_PORT (emulator + AI stubs)"
(
  cd "$REPO_ROOT/backend"
  export PORT="$BACKEND_PORT"
  export FIRESTORE_EMULATOR_HOST="$EMU_HOST"
  export GCP_PROJECT_ID="$UAT_PROJECT"
  export FIRESTORE_DATABASE_ID="(default)"
  export SESSION_SIGNING_KEY="$UAT_SESSION_SIGNING_KEY"
  export APP_AUTH_DEV_LOGIN_ENABLED=true
  export ADMIN_EMAILS="admin@uat.local"
  export CORS_ALLOWED_ORIGINS="http://localhost:${WEB_PORT}"
  export APP_UAT_STUBS_ENABLED=true
  # A dummy, non-empty Gemini key makes GeminiConfig build the shared client
  # (no network at construction), which lets the medication feature's drug
  # services wire so the meds CRUD + catalog/custom-entry flows work. We never
  # make a real Gemini call in UAT — the AI-heavy features below stay disabled,
  # and the meds flow uses custom entry / catalog, not AI drug lookup.
  export GEMINI_API_KEY=uat-dummy-gemini-key
  # AI-heavy features stay off (their endpoints aren't exercised in UAT).
  export APP_GOALS_ENABLED=false
  export APP_WORKOUT_PROGRAMS_ENABLED=false
  export APP_DEXA_ENABLED=false
  export APP_BLOODTEST_ENABLED=false
  export APP_NUTRITION_CAPTURE_ENABLED=false
  export APP_NUTRITION_IMAGES_ENABLED=false
  export APP_EXERCISES_MEDIA_ENABLED=false
  export APP_EXERCISES_ENRICH_ENABLED=false
  export APP_FCM_ENABLED=false
  export APP_EQUIPMENT_PARSER_API_KEY=uat-parser-key
  ./gradlew bootRun --console=plain
) > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
wait_for "http://localhost:${BACKEND_PORT}/actuator/health" "backend" 90

# --- 3. Web (UAT_AUTH_ENABLED; env passed inline so we never clobber .env.local) ---
echo "==> Starting web on :$WEB_PORT (UAT dev sign-in)"
(
  cd "$REPO_ROOT/web"
  export UAT_AUTH_ENABLED=1
  export BACKEND_URL="http://localhost:${BACKEND_PORT}"
  export AUTH_SECRET="$UAT_AUTH_SECRET"
  # Grant the UAT admin identity admin in the web gate too (mirrors the
  # backend ADMIN_EMAILS so admin pages are reachable in UAT).
  export ADMIN_EMAILS="admin@uat.local"
  export AUTH_GOOGLE_ID="uat-dummy-google-id"
  export AUTH_GOOGLE_SECRET="uat-dummy-google-secret"
  export PORT="$WEB_PORT"
  pnpm dev --port "$WEB_PORT"
) > "$LOG_DIR/web.log" 2>&1 &
WEB_PID=$!
wait_for "http://localhost:${WEB_PORT}/auth/dev" "web" 90

echo
echo "  Emulator: ${EMU_HOST}"
echo "  Backend:  http://localhost:${BACKEND_PORT}  (PID $BACKEND_PID)"
echo "  Web:      http://localhost:${WEB_PORT}      (PID $WEB_PID)"
echo

if $SERVE; then
  echo "  --serve: stack is up. Sign in at http://localhost:${WEB_PORT}/auth/dev"
  echo "  Ctrl-C to stop."
  wait
else
  echo "==> Running UAT suite"
  ( cd "$REPO_ROOT/uat" && ./gradlew test \
      -Duat.webBaseUrl="http://localhost:${WEB_PORT}" \
      -Duat.backendBaseUrl="http://localhost:${BACKEND_PORT}" \
      -Duat.firestoreEmulatorHost="${EMU_HOST}" \
      -Duat.firestoreProjectId="${UAT_PROJECT}" \
      -Duat.headless="${UAT_HEADLESS}" --console=plain )
  STATUS=$?
  echo "==> UAT suite exit: $STATUS"
  exit $STATUS
fi
