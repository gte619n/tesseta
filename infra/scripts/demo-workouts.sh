#!/usr/bin/env bash
set -euo pipefail

# Demo runner for the redesigned Workouts tab (IMPL-WEB-WORKOUT-01).
#
# Sibling of uat.sh, but tuned to SHOW the workout feature rather than test it:
#   - workout programs ENABLED (uat.sh disables them),
#   - AI chat clients stubbed (APP_UAT_STUBS_ENABLED) so nothing needs a real key,
#   - dev-login enabled so we can mint sessions + seed over REST,
#   - web trusts the Tailscale host so the app is reachable over the tailnet.
#
# Boots Firestore emulator + backend + web and STAYS UP (Ctrl-C to stop). Seed
# with infra/scripts/seed-demo-workouts.sh once it's healthy.
#
# Env overrides: BACKEND_PORT (8080), WEB_PORT (3000), EMU_PORT (8081),
# DEMO_PROJECT (demo-workouts), PUBLIC_URL (for Auth.js host trust).

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BACKEND_PORT="${BACKEND_PORT:-8080}"
WEB_PORT="${WEB_PORT:-3000}"
EMU_PORT="${EMU_PORT:-8081}"
EMU_HOST="127.0.0.1:${EMU_PORT}"
DEMO_PROJECT="${DEMO_PROJECT:-demo-workouts}"
PUBLIC_URL="${PUBLIC_URL:-http://localhost:${WEB_PORT}}"

DEMO_SESSION_SIGNING_KEY="demo-session-signing-key-0123456789-abcdef"
DEMO_AUTH_SECRET="demo-fixed-authjs-secret-for-local-demo-0123456789"

require() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }; }
require firebase; require java; require pnpm; require curl

LOG_DIR="${DEMO_LOG_DIR:-$(mktemp -d)}"
mkdir -p "$LOG_DIR"
echo "==> logs in $LOG_DIR"
echo "$LOG_DIR" > /tmp/demo-workouts.logdir

kill_tree() {
  local pid="$1"; [[ -z "$pid" ]] && return 0
  for child in $(pgrep -P "$pid" 2>/dev/null); do kill_tree "$child"; done
  kill "$pid" 2>/dev/null || true
}
cleanup() {
  echo; echo "==> Stopping"
  kill_tree "${WEB_PID:-}"; kill_tree "${BACKEND_PID:-}"; kill_tree "${EMU_PID:-}"
  for p in "$WEB_PORT" "$BACKEND_PORT" "$EMU_PORT"; do
    lsof -tiTCP:"$p" -sTCP:LISTEN 2>/dev/null | xargs -r kill 2>/dev/null || true
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

wait_for() {
  local url="$1" label="$2" tries="${3:-90}"
  for ((i=0; i<tries; i++)); do curl -sf "$url" >/dev/null 2>&1 && { echo "    $label ready"; return 0; }; sleep 2; done
  echo "    $label did NOT come up — see $LOG_DIR" >&2; return 1
}

echo "==> Firestore emulator on :$EMU_PORT"
( cd "$REPO_ROOT" && firebase emulators:start --only firestore \
    --project "$DEMO_PROJECT" --config infra/uat/firebase.json ) > "$LOG_DIR/emulator.log" 2>&1 &
EMU_PID=$!
wait_for "http://${EMU_HOST}/" "emulator" 60 || true

echo "==> Backend on :$BACKEND_PORT (emulator + workouts enabled + AI stubs)"
(
  cd "$REPO_ROOT/backend"
  export PORT="$BACKEND_PORT"
  export FIRESTORE_EMULATOR_HOST="$EMU_HOST"
  export GCP_PROJECT_ID="$DEMO_PROJECT"
  export FIRESTORE_DATABASE_ID="(default)"
  export SESSION_SIGNING_KEY="$DEMO_SESSION_SIGNING_KEY"
  export APP_AUTH_DEV_LOGIN_ENABLED=true
  export ADMIN_EMAILS="admin@demo.local"
  export CORS_ALLOWED_ORIGINS="${PUBLIC_URL},http://localhost:${WEB_PORT}"
  export APP_UAT_STUBS_ENABLED=true
  export GEMINI_API_KEY=demo-dummy-gemini-key
  # Local/dev: mint an ephemeral RSA key for the third-party OAuth platform
  # rather than requiring PLATFORM_RSA_KEY (prod-only secret).
  export PLATFORM_ALLOW_EPHEMERAL_KEY=true
  # Workouts + progression ON so the redesigned tab has real data behind it.
  export APP_WORKOUT_PROGRAMS_ENABLED=true
  export APP_GOALS_ENABLED=false
  export APP_DEXA_ENABLED=false
  export APP_BLOODTEST_ENABLED=false
  export APP_NUTRITION_CAPTURE_ENABLED=false
  export APP_NUTRITION_IMAGES_ENABLED=false
  export APP_EXERCISES_MEDIA_ENABLED=false
  export APP_EXERCISES_ENRICH_ENABLED=false
  export APP_FCM_ENABLED=false
  export APP_EQUIPMENT_PARSER_API_KEY=demo-parser-key
  ./gradlew bootRun --console=plain
) > "$LOG_DIR/backend.log" 2>&1 &
BACKEND_PID=$!
wait_for "http://localhost:${BACKEND_PORT}/actuator/health" "backend" 120

echo "==> Web on :$WEB_PORT (UAT dev sign-in; host-trust for ${PUBLIC_URL})"
(
  cd "$REPO_ROOT/web"
  export UAT_AUTH_ENABLED=1
  export BACKEND_URL="http://localhost:${BACKEND_PORT}"
  export AUTH_SECRET="$DEMO_AUTH_SECRET"
  export AUTH_URL="$PUBLIC_URL"
  export AUTH_TRUST_HOST=true
  export ADMIN_EMAILS="admin@demo.local"
  export AUTH_GOOGLE_ID="demo-dummy-google-id"
  export AUTH_GOOGLE_SECRET="demo-dummy-google-secret"
  export PORT="$WEB_PORT"
  pnpm dev --port "$WEB_PORT"
) > "$LOG_DIR/web.log" 2>&1 &
WEB_PID=$!
wait_for "http://localhost:${WEB_PORT}/auth/dev" "web" 120

echo
echo "  Emulator: ${EMU_HOST}"
echo "  Backend:  http://localhost:${BACKEND_PORT}  (PID $BACKEND_PID)"
echo "  Web:      http://localhost:${WEB_PORT}      (PID $WEB_PID)"
echo "  Public:   ${PUBLIC_URL}"
echo "  Sign in:  ${PUBLIC_URL}/auth/dev?userId=demo"
echo "==> Stack is up. Ctrl-C to stop."
touch /tmp/demo-workouts.ready
wait
