# IMPL-20 decision log

Decisions made while implementing [IMPL-20](../specs/IMPL-20-exercise-admin-redesign.md)
(exercise admin redesign) and [ADR-0017](../decisions/ADR-0017-gcs-thumbnail-cloud-function.md)
(thumbnail Cloud Function). The four headline decisions were ratified by Evan in
the design interview; the rest are implementation-level judgment calls made
during the parallel build, recorded here for review.

## Ratified in the design interview

| # | Decision | Choice |
|---|---|---|
| 1 | "Reviewed" semantics | **New separate boolean** `reviewed`, independent of `mediaStatus`/`planStatus` |
| 2 | Reference-image scope | **Unified picker** — own candidates *and* external library refs, persisted as `groundingImageUrls`, overridable per-regen |
| 3 | Thumbnail deployment | **Cloud Function Gen2**, GCS-finalize trigger |
| 4 | Catalog scale | **352 ⇒ client-side** filter/sort over a slim summary projection |
| 5 | List vs grid | **Both**, Finder-style view toggle over one shared data layer |

## Backend

1. **Own-bucket URL detection.** A grounding URL counts as an "own object" when
   it starts with `https://storage.googleapis.com/<exercise-media-bucket>/`
   (bucket from `app.exercises.bucket`, the same value `ExerciseMediaStorage`
   mints URLs with). Mirrors `ExerciseMediaStorage.deleteByUrl`'s existing
   prefix check so detection is consistent with how we write URLs. Own objects
   are read via the GCS SDK (`Storage.get(BlobId).getContent()`), not HTTP —
   avoids public-ACL/signing assumptions; the 8 MB cap still applies.

2. **Effective grounding-set fallback (three cases).** `resolveGrounding`
   distinguishes: (i) request `referenceImageUrls != null` → use exactly that
   list, where **empty = explicitly no grounding** (no fallback); (ii) request
   null but persisted `groundingImageUrls` non-empty → use persisted; (iii)
   request null *and* persisted empty → fall back to the IMPL-19
   `reference`-based resolution. Case (iii) was not spelled out in the spec; it
   keeps grounded generation working for the ~37 reference-matched exercises
   that have no explicit selection yet, so the change is non-regressive.

3. **External grounding URLs are fetched directly, not re-scraped.** Entries in
   `groundingImageUrls` are already concrete image URLs (own GCS or
   `reference.images`), so they're fetched directly. The per-source page-scrape
   path (Wikimedia / jefit / og:image) still applies only when resolving from
   the `reference` object (fallback case iii). Matches the picker contract,
   which surfaces `reference.images` (direct image URLs).

4. **Regenerate-media never persists the selection.** Per the spec invariant,
   the only write path for the grounding set is `PUT /{id}/grounding`. The
   regen endpoint treats `referenceImageUrls` as a transient per-run override
   and writes nothing. Resolved bytes are never persisted.

5. **Request DTO shapes & lenient nulls.** `SetReviewedRequest(boolean reviewed)`,
   `GroundingRequest(List<String> imageUrls)`. Null bodies are tolerated
   (`reviewed` defaults false; `imageUrls` defaults to clearing the set),
   matching the lenient style of the existing `RegenerateMediaRequest` /
   `SavePlanRequest` handlers.

6. **One `/catalog` endpoint serves both projections.** Return type widened to
   `List<?>`; `?view=summary` selects `ExerciseSummaryResponse`, no param keeps
   the full `ExerciseResponse` (spec requires the full response to stay).
   `frameImageUrls` is ordered by plan `order` (joining frames by key) when a
   plan exists, else by legacy frame `order`; nulls preserved for frames with no
   active image.

7. **New `Exercise` fields appended at the record tail** (after
   `aliasOfExerciseId`) to localize the diff; all positional constructors
   (7 in `ExerciseService`, plus `WorkoutHistoryImporter`) updated. Firestore
   `toExercise` reads them with `false` / `List.of()` defaults so legacy docs
   load cleanly; `toBody` writes them merge-safe.

## Thumbnail Cloud Function & infra

8. **Dedicated least-privilege SA `exercise-thumbnails-fn`.** Distinct from the
   shared `health-fitness-runtime`; granted only `roles/storage.objectAdmin` on
   the single exercise-media bucket, so a thumbnailing bug can't reach other
   buckets.

9. **SA grant lives in both bootstrap and deploy.** Canonical create+grant in
   `bootstrap-gcp.sh` (the SA/bucket home per repo precedent); the deploy script
   also create-if-absents the SA and re-applies the grant so it runs standalone
   (idempotent), matching the per-job scripts' defensive pattern.

10. **Thumbnail rendering settings.** Output `image/webp`, `quality: 80`,
    `fit: inside` + `withoutEnlargement: true` (bounds longest edge to 320px,
    preserves aspect, never upscales), `.rotate()` to honor EXIF orientation
    before resizing.

11. **Path parsing + loop guard.** Primary guard: skip any object whose path
    contains `/thumb/`. Secondary structural filter: regex
    `^exercises/([^/]+)/([^/]+)\.([^./]+)$` captures `{id}`/`{name}`, requires an
    extension, and excludes anything nested deeper. Non-image content types are
    skipped defensively.

12. **Deploy settings.** `--memory=512Mi --cpu=1 --timeout=120s
    --max-instances=10 --retry`. 512Mi gives sharp headroom for ~1024px source
    decodes; max-instances caps backfill bursts; `--retry` is safe because the
    handler is idempotent (overwriting the same thumb path).

13. **GCS service-agent `pubsub.publisher` grant** added in the deploy script —
    Gen2 storage finalize triggers route through Eventarc/Pub-Sub and this
    one-time grant is a required, easy-to-miss prerequisite.

## Web

14. **View-mode persistence.** `localStorage` key `exerciseAdminViewMode`
    (`"list" | "grid"`), default `grid`. SSR-safe: default rendered server-side,
    persisted value read in a client effect; reads/writes wrapped in try/catch
    for private-mode/unavailable storage.

15. **Review-tab preset.** `/admin/exercises/review` redirects to
    `/admin/exercises/catalog?preset=needs-review`. The catalog parses the param
    into a dedicated AND-applied flag (`mediaStatus === NEEDS_REVIEW ||
    planStatus === NEEDS_REVIEW`), independent of the status selects so it
    composes with other filters. `ExerciseSubTabs` relabels "Review" →
    "Needs review" and highlights on the `preset` param.

16. **"Recently updated" sort dropped.** The slim summary projection
    deliberately omits `updatedAt` (image-thin goal); adding it back would
    defeat that. Sort options are **name (A–Z)** and **image count**; server's
    natural order is the baseline. *If "recently updated" is wanted, add
    `updatedAt` to `ExerciseSummaryResponse` — a one-field change.*

17. **Thumbnail consumption.** Pure string transform `thumbUrl()`; null / empty /
    already-`/thumb/` / non-convention URLs returned unchanged. Tiles use a plain
    `<img>` (not `next/image`) with an `onError` swap to the full URL, since the
    derived thumb may 404 before the function runs and the optimizer would
    otherwise choke on it.

18. **Detail drawer.** A single `ModalBackdrop` modal opened by id from either
    view; lazy-loads full detail via a `loadExercise` server action
    (`GET /{id}`). Reviewed toggle is optimistic locally and in the container's
    override map (reverts on failure). Merge uses an inline select (replacing the
    old `window.prompt`).

19. **Reference-picker UX.** Own candidates and external references shown in two
    labelled sections, each a toggleable thumbnail (selected = accent ring +
    check), pre-seeded from `groundingImageUrls`. "Save grounding set" persists
    via `PUT /grounding`; inside the regen modal the live selection is also sent
    as `referenceImageUrls` for that run (undefined until touched ⇒ backend uses
    the persisted set).

20. **`AdminExerciseReview.tsx` left in place.** It still hosts `regenTargets` +
    `ExerciseAdminActions` (imported by the drawer); its top-level component is
    now unused but harmless. *Candidate for a follow-up cleanup once the drawer
    fully absorbs those helpers.*

## Follow-ups / open items

- **Backfill thumbnails** for the ~existing exercise images after the function's
  first deploy (re-list + reprocess; see the function README).
- **Per-frame grounding assignment** remains out of scope (the picker is a
  per-exercise pool).
- **Client (user/Android) thumbnail consumption** not wired — the path
  convention leaves it open without a migration.
- Re-add **`updatedAt`** to the summary projection if "recently updated" sort is
  desired (decision 16).
- Remove the now-unused `AdminExerciseReview` top-level component in a cleanup
  pass (decision 20).
