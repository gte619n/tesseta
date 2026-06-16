# exercise-thumbnails

The repo's **first Cloud Function** — a Gen2, GCS-triggered function that
generates webp thumbnails for exercise media. See
[ADR-0017](../../docs/decisions/ADR-0017-gcs-thumbnail-cloud-function.md) and the
Infra section of
[IMPL-20](../../docs/specs/IMPL-20-exercise-admin-redesign.md).

## What it does

- **Trigger:** GCS `google.cloud.storage.object.v1.finalized` (CloudEvent) on
  `health-fitness-160-exercise-media`.
- **Behavior:** for `exercises/{id}/{name}.{ext}` it writes a webp thumbnail
  (max edge **320px**, aspect preserved) to `exercises/{id}/thumb/{name}.webp`
  in the **same** bucket, copying `Cache-Control: public, max-age=31536000, immutable`.
- **Loop guard:** any object whose path contains `/thumb/` is skipped, so the
  function never reprocesses its own output. Non-image objects are skipped
  defensively.
- **No Firestore writes.** The web derives the thumb URL by convention and falls
  back to the full image on a miss. Overwriting an existing thumb is fine
  (idempotent).

## Runtime

- Node 20 + [`sharp`](https://sharp.pixelplumbing.com/) for the resize.
- Entry point: `generateThumbnail` (exported via the functions-framework
  CloudEvent signature).

## Local run

```bash
npm install
npm start            # functions-framework on http://localhost:8080
```

Send a synthetic finalize CloudEvent (the object must already exist in the
bucket and you must be authenticated to GCS):

```bash
curl -X POST http://localhost:8080 \
  -H 'Content-Type: application/json' \
  -H 'ce-id: 1' \
  -H 'ce-specversion: 1.0' \
  -H 'ce-type: google.cloud.storage.object.v1.finalized' \
  -H 'ce-source: //storage.googleapis.com/projects/_/buckets/health-fitness-160-exercise-media' \
  -d '{
        "bucket": "health-fitness-160-exercise-media",
        "name": "exercises/abc123/frame-start.png",
        "contentType": "image/png"
      }'
```

Syntax check only (no GCS access needed):

```bash
npm run check        # node --check index.js
```

## Deploy

Deployed by [`infra/scripts/deploy-thumbnail-fn.sh`](../../infra/scripts/deploy-thumbnail-fn.sh),
which mirrors the per-job deploy scripts. The function's runtime service account
(`exercise-thumbnails-fn`) and its `roles/storage.objectAdmin` grant on the
bucket are provisioned by
[`infra/scripts/bootstrap-gcp.sh`](../../infra/scripts/bootstrap-gcp.sh).

```bash
bash infra/scripts/deploy-thumbnail-fn.sh
```

Required APIs (Cloud Functions, Eventarc, Cloud Run, Artifact Registry, Cloud
Build) are enabled by `infra/scripts/enable-apis.sh`.

## Backfill existing images

After the first deploy, existing objects predate the trigger. Run the backfill
script — it applies the **same transform** as `index.js` directly (via
Application Default Credentials), writing any missing `thumb/` objects. It does
not modify originals and is idempotent (existing thumbs are skipped).

```bash
gcloud auth application-default login   # if ADC isn't already set up
node backfill.js                        # generate missing thumbs
FORCE=1 node backfill.js                # rewrite thumbs even if present
CONCURRENCY=12 node backfill.js         # tune parallelism (default 8)
```

> Note: do **not** try to backfill via `gcloud storage objects update`
> (metadata patch) — that emits `metadataUpdated`, not `finalized`, so it does
> not fire this trigger. Re-uploading/copying an object onto a new generation
> does fire `finalized`, but the script above is simpler and leaves originals
> (and their immutable cache headers) untouched.
