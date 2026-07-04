#!/usr/bin/env bash
# Canary promotion: smoke-test a Cloud Run revision that was deployed with
# `--no-traffic --tag=candidate`, then shift 100% of traffic to it. If the smoke
# test fails, traffic is left untouched (the old revision keeps serving), so a
# bad build never reaches users.
#
# Usage: canary-promote.sh SERVICE REGION HEALTH_PATH
# Env:   PROJECT_ID (required)
set -euo pipefail

SERVICE="${1:?usage: canary-promote.sh SERVICE REGION HEALTH_PATH}"
REGION="${2:?region required}"
HEALTH_PATH="${3:-/}"
PROJECT="${PROJECT_ID:?PROJECT_ID env required}"

# The tagged revision gets its own stable URL (candidate---SERVICE-...run.app).
CANDIDATE_URL=$(
  gcloud run services describe "$SERVICE" \
    --region="$REGION" --project="$PROJECT" --format=json |
    python3 -c "import sys,json; print(next(t['url'] for t in json.load(sys.stdin)['status']['traffic'] if t.get('tag')=='candidate'))"
)

echo "==> Smoke-testing candidate: ${CANDIDATE_URL}${HEALTH_PATH}"
code=000
for _ in $(seq 1 30); do
  code=$(curl -s -o /dev/null -w '%{http_code}' "${CANDIDATE_URL}${HEALTH_PATH}" || echo 000)
  # Accept any non-error response (2xx/3xx) — the web root redirects to sign-in.
  if [ "$code" -ge 200 ] && [ "$code" -lt 400 ]; then
    echo "    candidate healthy (HTTP ${code})"
    echo "==> Promoting candidate to 100% traffic"
    gcloud run services update-traffic "$SERVICE" \
      --region="$REGION" --project="$PROJECT" --to-tags=candidate=100
    echo "    promoted. Previous revision retained — roll back with infra/scripts/rollback.sh"
    exit 0
  fi
  sleep 3
done

echo "!! Candidate smoke test failed (last HTTP ${code}). Traffic UNCHANGED; not promoting." >&2
exit 1
