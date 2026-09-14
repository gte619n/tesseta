# IMPL-SEC-01 — Private Media Buckets (signed-URL serving)

> Status: **planned** · Created 2026-09-13 · Source: audit finding SEC-012
> (`docs/audit/2026-09-13/`), de-scoped from the safety batch per decision
> DEC-002 because it is a cross-client breaking change, not a config flip.

## Problem

Six GCS buckets carry `allUsers:objectViewer`
(`infra/scripts/bootstrap-gcp.sh:78,94`; `infra/README.md:42-44`): nutrition
meal photos (**user PHI**), plus food/studio/drug/equipment/exercise/location
imagery (shared reference data, lower sensitivity). Clients render images by
fetching the **raw public URL** built server-side
(`integrations/nutrition/MealPhotoStorage.java:110`,
`integrations/nutrition/FoodImageStorage.java:253`, and the
drug/equipment/exercise/location `*Storage` siblings). For meal photos the
public URL is also persisted as `photoRef` on `FoodEntry` and re-read
server-side for adjust/leftovers (`core/nutrition/MealCaptureService.java`).

Any meal-photo URL is therefore world-fetchable without authentication — PHI
exposure — but flipping the bucket private naively breaks every client image
render.

## Why this is not a quick flip

- **6 buckets, 3 clients** (web `<img>`, android Coil, backend re-reads) all
  assume anonymous GCS HTTP GET.
- **Persisted URLs**: `photoRef` values already stored on entries are absolute
  public URLs; making the bucket private orphans them for display.
- Server-side re-reads (`MealPhotoReader.read(photoRef)`) use the authenticated
  Storage client and would keep working — so only the *client display* path
  breaks, which is exactly the part that needs new serving infrastructure.

## Approach (incremental, reversible)

Prioritize the PHI bucket (`-nutrition-photos`); treat catalog buckets
(food/drug/equipment/exercise) as a lower-priority second wave since they hold
non-personal reference imagery.

**Phase 1 — backend signed-URL serving (no bucket change yet).**
1. Add `SignedUrlService` wrapping the GCS `Storage` client to mint V4
   read-signed URLs (TTL ~15 min) for an object.
2. Add an authenticated redirect endpoint, e.g.
   `GET /api/me/nutrition/photo/{entryId}` → resolves the entry's `photoRef`,
   authorizes it belongs to the caller (per-user scoping — ADR-0021), returns
   `302` to a freshly signed URL. Never expose the object path directly.
3. Change entry DTOs to carry a stable `photoUrl` pointing at this endpoint
   (not the GCS URL). Keep `photoRef` internal (server-side re-read key).

**Phase 2 — client migration.** Web and android load the meal photo via the
new endpoint (Coil follows 302s; `<img>` follows redirects). Ship and confirm
rendering on both before touching bucket IAM.

**Phase 3 — flip the bucket.** Remove `allUsers:objectViewer` on
`-nutrition-photos` (uniform bucket-level access, private), update
`bootstrap-gcp.sh` + add the terraform IAM. Verify server-side re-reads and the
new signed-URL path both work; old persisted public URLs now 403 on direct
fetch (intended) but render via the endpoint.

**Phase 4 — catalog buckets (optional/second wave).** Same pattern, or a
documented risk-accept that generic reference images are non-PHI and may stay
public (decision to record at the time).

## Verification

- Meal photo renders on web + android via the endpoint on cellular.
- Direct GCS URL to a meal photo returns 403 after Phase 3.
- Server-side adjust/leftovers still re-read the photo (authenticated client).
- Cross-user access to `/api/me/nutrition/photo/{entryId}` for a foreign entry
  returns 403/404 (add a test — pairs with the audit's TEST-001 gap).

## Effort & risk

~3–5 operator-days across the four phases (Phase 1–3 for meal photos ≈ 2–3d).
Risk: medium — staged so no phase breaks rendering; the bucket flip (Phase 3)
is last and independently reversible (re-add the IAM binding).

**Do-nothing cost:** meal photos remain anonymously world-readable; becomes a
disclosed-breach risk the moment a non-owner user's photos are in the bucket
(trigger: user #2). Cross-reference COMP-001 (the published privacy policy
already promises "you can only ever reach your own records").
