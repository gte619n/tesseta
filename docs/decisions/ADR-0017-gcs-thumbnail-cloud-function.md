# ADR-0017: GCS thumbnail generation via a Cloud Function (Gen2)

- Status: Accepted
- Date: 2026-06-16
- Relates to: [IMPL-20](../specs/IMPL-20-exercise-admin-redesign.md),
  [IMPL-19](../specs/IMPL-19-dynamic-demo-frames.md) (dynamic demo frames),
  [ADR-0001](ADR-0001-three-component-architecture.md) (three-component architecture)

## Context

The redesigned exercise admin ([IMPL-20](../specs/IMPL-20-exercise-admin-redesign.md))
renders a tile grid over 352 exercises, each with 1–5 frames and several
candidate images per frame. Loading full-resolution images (stored
`public, max-age=31536000, immutable` at up to ~1024px) for a dense grid is
wasteful; the grid needs small thumbnails.

No thumbnail generation exists anywhere in the repo today. Exercise images reach
GCS by two paths, both written by the backend to
`health-fitness-160-exercise-media`:

1. **AI-generated** frames (`gemini-3.1-flash-image-preview`) written by
   `ExerciseMediaStorage`.
2. **Admin uploads** via `POST /api/admin/exercises/{id}/upload-frame`.

The repo has **no Cloud Functions**; all async/batch work runs as Cloud Run
**jobs** reusing the backend container image, gated by Spring profiles
(`backfill-exercise-media`, `goals-sustained-reeval`, …). Buckets are created by
shell scripts (`infra/scripts/bootstrap-gcp.sh`), not Terraform.

Three shapes were considered:

1. **Inline in the backend** — resize synchronously at write time, plus a
   backfill job for existing objects.
2. **Eventarc → Cloud Run service** — reuse the backend image / Java, matching
   the existing job precedent.
3. **Cloud Function (Gen2)** triggered directly by GCS object finalize.

## Decision

**Generate thumbnails in a standalone Gen2 Cloud Function triggered by GCS
`object.finalized` on the exercise-media bucket.**

- **Trigger.** `google.cloud.storage.object.v1.finalized` on
  `health-fitness-160-exercise-media`. Because *both* generated and uploaded
  images land in this bucket, one finalize trigger covers both write paths — no
  backend coupling on either side.
- **Runtime.** Node 20 + `sharp`. Output webp, max edge **320px**, copying the
  source's `public, max-age=31536000, immutable` cache headers.
- **Addressing by convention.** `exercises/{id}/{name}.{ext}` →
  `exercises/{id}/thumb/{name}.webp`. The function writes nothing to Firestore;
  the web derives the thumbnail URL from the original and falls back to the full
  image on a miss (covering the brief window before the thumb exists).
- **Loop guard.** Skip any object whose path contains `/thumb/`, so the
  function never reprocesses its own output.
- **Code & deploy.** `functions/exercise-thumbnails/`; deployed by
  `infra/scripts/deploy-thumbnail-fn.sh` (mirrors the per-job deploy scripts).
  A dedicated SA gets `roles/storage.objectAdmin` on the bucket. Existing images
  are backfilled once after first deploy (re-list + reprocess).

This is the repo's **first Cloud Function**; the PR calls out the new
runtime/deploy pattern explicitly.

## Rationale

- **Both write paths, zero backend change.** A finalize trigger fires for
  generated *and* uploaded images alike. Inline resizing (option 1) would have
  to be wired into every current and future write site and still needs a
  backfill; the trigger is write-site-agnostic.
- **Right tool for image work.** `sharp` is the de-facto Node image library —
  fast, webp-native, tiny. The Java image stack on the backend image (option 2)
  is heavier for a pure resize and would pull the backend's full dependency set
  into a hot per-object path.
- **Convention over a stored field.** Deriving the thumb URL avoids a Firestore
  write from the function (no extra IAM, no write amplification, no
  read-modify-write race against concurrent regenerations) and keeps the
  `Exercise` document unchanged. The cost is a possible early miss, absorbed by
  the web's `onError` fallback.
- **Cheap and isolated.** Per-object, event-driven, scales to zero — no
  always-on service, and a failure blast radius limited to thumbnailing.

## Consequences

- The repo gains a **second runtime** (Node) and a **new deploy pattern**
  (Cloud Function) alongside the Cloud Run jobs. `enable-apis.sh` must enable
  Cloud Functions + Eventarc; the deploy script joins the per-job scripts.
- Thumbnails are **eventually consistent**: a tile may briefly show the full
  image until the thumb is written. Acceptable for an admin grid.
- The function is **bucket-scoped to exercise media**. Extending thumbnailing to
  the other image buckets (nutrition, equipment, gym, medication) would mean
  parameterizing the trigger/bucket — deferred until a second consumer wants it.
- Thumbnails are **not yet read by user/Android clients** (IMPL-20 scope is the
  admin grid); the path convention leaves that door open without a migration.
- Buckets remain script-provisioned (no Terraform). If function infra grows, a
  later move to Terraform is reasonable but not required here.

## Alternatives considered

- **Inline backend resize + backfill job** (rejected): couples thumbnailing to
  every image write site, pulls a Java image stack into request/generation
  paths, and still needs a separate backfill — more surface for the same result.
- **Eventarc → Cloud Run service on the backend image** (rejected): matches the
  existing job precedent, but ships the entire backend container and JVM warmup
  cost to resize one image per event; `sharp` in a Gen2 function is dramatically
  lighter for this workload.
- **Storing `thumbnailUrl` on each `DemoFrame` candidate** (rejected):
  restructures `imageCandidates` (today a `List<String>`), forces the function
  to write Firestore (new IAM + races with concurrent regen), and buys nothing
  the path convention + `onError` fallback doesn't already provide.
