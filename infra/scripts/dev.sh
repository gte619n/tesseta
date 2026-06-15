#!/usr/bin/env bash
set -euo pipefail

# Pulls the OAuth + Auth.js secrets out of Secret Manager and runs the backend
# and web app side-by-side for local end-to-end auth testing — exposed over this
# machine's Tailscale MagicDNS name so other tailnet devices can reach them.
#
# Web:     served over HTTPS at https://<magic-dns>/ via `tailscale serve`
#          (TLS-terminated on 443, proxied to a local Next dev server on :3000).
#          HTTPS is required because Google OAuth rejects http:// redirect URIs
#          on any non-localhost host.
# Backend: http://<magic-dns>:8090 (plain; the web server proxies to it in-process
#          over loopback, and the tailnet can hit it directly for native-client
#          testing).
#
# Both hit real GCP services — Firestore (default) database, KMS via ADC,
# Secret Manager. Run `gcloud auth application-default login` once before
# the first invocation so KMS calls succeed.
#
# Prerequisites: Tailscale up + logged in, and HTTPS certificates enabled for the
# tailnet (admin console -> Features -> HTTPS Certificates). Ctrl-C tears down
# both servers AND the `tailscale serve` config.

PROJECT_ID="health-fitness-160"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }
}
require gcloud
require pnpm

# --- Tailscale MagicDNS binding ---------------------------------------------
# The CLI isn't always on PATH (the macOS GUI app ships it inside the bundle),
# so fall back to the bundled binary. Next dev runs on a local-only :3000 that
# `tailscale serve` fronts with HTTPS on 443; the backend listens on :8090.
WEB_PORT=3000
BACKEND_PORT=8090

TAILSCALE_BIN="$(command -v tailscale || true)"
if [[ -z "$TAILSCALE_BIN" && -x "/Applications/Tailscale.app/Contents/MacOS/Tailscale" ]]; then
  TAILSCALE_BIN="/Applications/Tailscale.app/Contents/MacOS/Tailscale"
fi
[[ -n "$TAILSCALE_BIN" ]] || { echo "Missing required tool: tailscale" >&2; exit 1; }

TS_HOST="$("$TAILSCALE_BIN" status --json 2>/dev/null \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["Self"]["DNSName"].rstrip("."))' 2>/dev/null || true)"
if [[ -z "$TS_HOST" ]]; then
  echo "Could not resolve this machine's Tailscale MagicDNS name." >&2
  echo "Is tailscaled running and logged in? Try: $TAILSCALE_BIN status" >&2
  exit 1
fi
# Web over HTTPS on 443 (no port). Backend over HTTPS on 8443 (so the Android
# debug build can reach it without a cleartext-traffic exception) and also plain
# http://…:8090 for direct/curl use.
BACKEND_HTTPS_PORT=8443
WEB_ORIGIN="https://${TS_HOST}"
BACKEND_ORIGIN="http://${TS_HOST}:${BACKEND_PORT}"
BACKEND_HTTPS_ORIGIN="https://${TS_HOST}:${BACKEND_HTTPS_PORT}"

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
SESSION_SIGNING_KEY="$(secret session-signing-key)"

# --- Backend env ---
# Serve on :8090, all interfaces (Spring Boot's default bind) so the Tailscale
# MagicDNS name reaches it.
export SERVER_PORT="$BACKEND_PORT"
# IMPL-02 audience checks + CORS allow-list. Allow both the loopback and the
# Tailscale web origin so a browser on either can call the backend.
export OAUTH_ALLOWED_AUDIENCES
export CORS_ALLOWED_ORIGINS="http://localhost:${WEB_PORT},${WEB_ORIGIN}"
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
# ADR-0010: HS256 key for minting native-client session access tokens. Without
# it the local backend accepts Google tokens only and /api/auth/exchange 503s.
export SESSION_SIGNING_KEY

# --- Web env (.env.local) ---
WEB_ENV="${REPO_ROOT}/web/.env.local"
# AUTH_URL pins the canonical origin Auth.js uses to build the Google redirect_uri
# (so it points at the Tailscale name, not localhost). AUTH_TRUST_HOST is required
# off-Vercel when serving on a non-localhost host. BACKEND_URL stays loopback —
# the web server proxies to the backend in-process on the same machine.
cat > "$WEB_ENV" <<EOF
AUTH_SECRET=${AUTH_SECRET}
AUTH_GOOGLE_ID=${AUTH_GOOGLE_ID}
AUTH_GOOGLE_SECRET=${AUTH_GOOGLE_SECRET}
AUTH_URL=${WEB_ORIGIN}
AUTH_TRUST_HOST=true
BACKEND_URL=http://localhost:${BACKEND_PORT}
EOF
echo "    wrote $WEB_ENV"

# --- Run both ---

cleanup() {
  echo
  echo "==> Stopping"
  # Tear down the HTTPS proxy mappings so they don't linger after the dev servers.
  "$TAILSCALE_BIN" serve --https=443 off >/dev/null 2>&1 || true
  "$TAILSCALE_BIN" serve --https="${BACKEND_HTTPS_PORT}" off >/dev/null 2>&1 || true
  [[ -n "${BACKEND_PID:-}" ]] && kill "$BACKEND_PID" 2>/dev/null || true
  [[ -n "${WEB_PID:-}"     ]] && kill "$WEB_PID"     2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Front the local Next dev server with HTTPS on the MagicDNS name. This needs
# HTTPS certificates enabled for the tailnet; the first run provisions a cert.
echo "==> Configuring Tailscale Serve (HTTPS 443 -> http://127.0.0.1:${WEB_PORT})"
if ! "$TAILSCALE_BIN" serve --bg --https=443 "http://127.0.0.1:${WEB_PORT}"; then
  echo "tailscale serve failed. Enable HTTPS for your tailnet:" >&2
  echo "  admin console -> Settings -> Features -> HTTPS Certificates" >&2
  exit 1
fi
# Backend over HTTPS so the Android debug build can reach it (no cleartext).
echo "==> Configuring Tailscale Serve (HTTPS ${BACKEND_HTTPS_PORT} -> http://127.0.0.1:${BACKEND_PORT})"
"$TAILSCALE_BIN" serve --bg --https="${BACKEND_HTTPS_PORT}" "http://127.0.0.1:${BACKEND_PORT}" || true

echo "==> Starting backend"
(cd "${REPO_ROOT}/backend" && ./gradlew bootRun --console=plain) &
BACKEND_PID=$!

echo "==> Starting web"
# Bind loopback only — `tailscale serve` is the front door (HTTPS on 443).
# Invoke next directly (not `pnpm dev -- …`) so the flags reach next's parser
# instead of being forwarded after a literal `--` (which next reads as a dir arg).
(cd "${REPO_ROOT}/web" && pnpm exec next dev --turbopack --hostname 127.0.0.1 --port "${WEB_PORT}") &
WEB_PID=$!

echo
echo "  Web:     ${WEB_ORIGIN}  (HTTPS via tailscale serve -> local :${WEB_PORT}, PID $WEB_PID)"
echo "  Backend: ${BACKEND_HTTPS_ORIGIN}  (HTTPS for the Android app; also ${BACKEND_ORIGIN} / localhost:${BACKEND_PORT}, PID $BACKEND_PID)"
echo "  Try:     open ${WEB_ORIGIN}/me"
echo
echo "  Google OAuth redirect URI to authorize (Console -> Credentials -> Web client):"
echo "    ${WEB_ORIGIN}/api/auth/callback/google"
echo
echo "  Ctrl-C to stop both servers and remove the serve config."

wait
