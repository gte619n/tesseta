#!/usr/bin/env bash
# One-command rollback: shift 100% of a Cloud Run service's traffic back to the
# previous ready revision. Use when a promoted revision misbehaves.
#
# Usage: rollback.sh SERVICE [REGION]
# Env:   PROJECT_ID (defaults to health-fitness-160)
set -euo pipefail

SERVICE="${1:?usage: rollback.sh SERVICE [REGION]}"
REGION="${2:-us-central1}"
PROJECT="${PROJECT_ID:-health-fitness-160}"

# Revisions newest-first; index 0 is current, index 1 is the previous one.
mapfile -t REVISIONS < <(
  gcloud run revisions list --service="$SERVICE" \
    --region="$REGION" --project="$PROJECT" \
    --format='value(metadata.name)' --sort-by='~metadata.creationTimestamp'
)

PREVIOUS="${REVISIONS[1]:-}"
if [ -z "$PREVIOUS" ]; then
  echo "No previous revision to roll back to for ${SERVICE}." >&2
  exit 1
fi

echo "==> Rolling ${SERVICE} back to ${PREVIOUS}"
gcloud run services update-traffic "$SERVICE" \
  --region="$REGION" --project="$PROJECT" --to-revisions="${PREVIOUS}=100"
echo "    done."
