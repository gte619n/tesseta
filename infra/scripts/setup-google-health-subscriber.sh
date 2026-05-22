#!/usr/bin/env bash
set -euo pipefail

# Registers (or updates) the Google Health API webhook subscriber that points
# at this project's backend Cloud Run service. Idempotent — re-running picks
# up secret rotations and endpoint changes via PATCH.
#
# Prereqs:
#   - Google Health API enabled on the project.
#   - Backend Cloud Run service deployed and publicly reachable (Google
#     performs a domain-verification probe pair against the endpoint during
#     this call).
#   - `google-health-webhook-secret` in Secret Manager.
#   - `gcloud` authenticated with an account that has
#     `roles/healthapi.subscriberAdmin` (or equivalent custom role).

PROJECT_ID="${PROJECT_ID:-health-fitness-160}"
SUBSCRIBER_ID="${SUBSCRIBER_ID:-health-fitness-backend}"
BACKEND_URL="${BACKEND_URL:?BACKEND_URL must be set (e.g., https://health-fitness-backend-xxxx-uc.a.run.app)}"

# The Health API requires project NUMBER in the resource path, not project
# ID. Resolve once up-front.
PROJECT_NUMBER="$(gcloud projects describe "$PROJECT_ID" \
  --format='value(projectNumber)')"

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }
}
require gcloud
require curl
require jq

echo "==> Fetching webhook secret from Secret Manager"
WEBHOOK_SECRET="$(gcloud secrets versions access latest \
  --secret=google-health-webhook-secret \
  --project="$PROJECT_ID")"

echo "==> Fetching developer access token"
ACCESS_TOKEN="$(gcloud auth print-access-token)"

ENDPOINT_URI="${BACKEND_URL%/}/api/webhooks/google-health"

# Schema confirmed against https://developers.google.com/health/reference/rest/v4/projects.subscribers
#   - endpointAuthorization.secret: the full Authorization header value the
#     endpoint should accept. Google requires a scheme prefix, so we store
#     the secret as "Bearer <random>" in Secret Manager.
#   - subscriberConfigs: array; each entry pairs a list of dataTypes with a
#     create policy. AUTOMATIC = Google opens a subscription automatically
#     when a user grants the matching scope.
BODY="$(jq -n \
  --arg endpoint "$ENDPOINT_URI" \
  --arg secret "$WEBHOOK_SECRET" \
  '{
     endpointUri: $endpoint,
     endpointAuthorization: { secret: $secret },
     subscriberConfigs: [
       {
         dataTypes: ["weight", "body-fat"],
         subscriptionCreatePolicy: "AUTOMATIC"
       }
     ]
   }')"

echo "==> Checking whether subscriber already exists"
# The Health API's GET on a subscriber resource path returns 404 even for
# subscribers that LIST clearly shows exist, so we can't rely on GET as
# an existence check. LIST + jq filter is the only reliable approach.
LIST_RESP="$(curl -s -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Goog-User-Project: $PROJECT_ID" \
  "https://health.googleapis.com/v4/projects/${PROJECT_NUMBER}/subscribers")"
EXPECTED_NAME="projects/${PROJECT_NUMBER}/subscribers/${SUBSCRIBER_ID}"
EXISTS="$(printf '%s' "$LIST_RESP" \
  | jq --arg n "$EXPECTED_NAME" '[.subscribers[]?.name] | index($n) != null')"

if [[ "$EXISTS" == "true" ]]; then
  echo "    exists — PATCH"
  STATUS=$(curl -s -o /tmp/subscriber-resp.json -w "%{http_code}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-Goog-User-Project: $PROJECT_ID" \
    -H "Content-Type: application/json" \
    -X PATCH \
    "https://health.googleapis.com/v4/projects/${PROJECT_NUMBER}/subscribers/${SUBSCRIBER_ID}?updateMask=endpointUri,endpointAuthorization,subscriberConfigs" \
    -d "$BODY")
elif [[ "$EXISTS" == "false" ]]; then
  echo "    new — POST"
  STATUS=$(curl -s -o /tmp/subscriber-resp.json -w "%{http_code}" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "X-Goog-User-Project: $PROJECT_ID" \
    -H "Content-Type: application/json" \
    -X POST \
    "https://health.googleapis.com/v4/projects/${PROJECT_NUMBER}/subscribers?subscriberId=${SUBSCRIBER_ID}" \
    -d "$BODY")
else
  echo "    LIST returned unexpected shape:" >&2
  printf '%s\n' "$LIST_RESP" >&2
  exit 1
fi

if [[ "$STATUS" != "200" && "$STATUS" != "201" ]]; then
  echo "    write failed: $STATUS" >&2
  cat /tmp/subscriber-resp.json >&2
  exit 1
fi
echo "    OK ($STATUS)"

echo "==> Verifying"
# GET-by-name returns 404 even for existing subscribers (see existence
# check above), so we re-LIST and pick our entry out.
curl -s -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "X-Goog-User-Project: $PROJECT_ID" \
  "https://health.googleapis.com/v4/projects/${PROJECT_NUMBER}/subscribers" \
  | jq --arg n "projects/${PROJECT_NUMBER}/subscribers/${SUBSCRIBER_ID}" \
       '.subscribers[] | select(.name == $n)'
