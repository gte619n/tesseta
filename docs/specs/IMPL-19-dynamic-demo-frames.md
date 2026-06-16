# IMPL-19: Dynamic, reference-grounded exercise demo frames

## Goal of this spec

Replace the fixed **START / MID / END** demo-frame triad
([IMPL-14](IMPL-14-exercise-library.md)) with a **per-exercise frame plan** —
the right *number* of frames for each movement (1 for a hold, 2 for most
strength lifts, 3–5 for skill/flow movements), each with a teaching caption.

Two new inputs make this possible:

1. The **frame plan is model-derived and reference-grounded** — a
   `gemini-3.5-flash` pass reads each exercise plus its public-library
   `reference.url` (added Jun 2026; see commit `140eba0`) and returns the
   distinct positions a learner must see, with labels, captions, and a
   per-frame position prompt.
2. **Image generation is grounded image-to-image** — when a reference image is
   available for an exercise, its bytes are fed to
   `gemini-3.1-flash-image-preview` as a *pose reference* so the generated
   frame is biomechanically correct, rendered in our house style.

Backend + web in this spec; Android needs only a caption tweak (its viewer
already handles N frames).

## Decisions locked

| Decision | Choice |
|---|---|
| Frame count | **Dynamic, 1–5 per exercise** — not a fixed triad |
| Who decides the count | **Model-driven, reference-grounded** (`gemini-3.5-flash`), emitted as a reviewable `demoPlan` on the exercise |
| Plan vs frames | Separate concepts: **`FrameSpec`** (the plan — *what to show*, reviewed) and **`DemoFrame`** (the generated images, keyed to a spec) |
| Image grounding | **All sources, best-effort, transient.** Reference images are fetched at generation time, passed to the image model as a pose reference, and **never stored or displayed** |
| Image ownership | **Every stored/displayed image is ours** (house-style, `gemini-3.1-flash-image-preview`). Reference images are generation input only — so no licensing/attribution is carried |
| Models | `gemini-3.5-flash` (planning text) + `gemini-3.1-flash-image-preview` (frames) — both already approved; **no new ADR** |
| Review | Two human gates: **plan** (`planStatus`) and **media** (`mediaStatus`). Reviewing a plan is cheap and gates all downstream image cost |
| Backward compatibility | `demoPlan == null` ⇒ fall back to today's pattern-driven START/MID/END behavior; legacy frames keep rendering |
| Video | Out of scope — Veo stays on its own track ([ADR-0009](../decisions/ADR-0009-exercise-demo-video-veo.md)) |

## Concepts

**Frame plan (`demoPlan`)** — an ordered list of `FrameSpec`s describing the
distinct positions this exercise needs to teach. A static plank has one
(`hold`); a back squat has two (`start`, `bottom`); a Turkish get-up has
several. The plan, not a hardcoded enum, drives how many images are generated
and what each one shows.

**FrameSpec** — one planned position: a stable `key`, a UI `label`, a teaching
`caption`, and the `positionPrompt` fed to the image model. This replaces the
movement-pattern → position lookup (`ExerciseMovementPhases`) with a
per-exercise, reference-grounded description.

**DemoFrame** — the generated image(s) for one `FrameSpec`, joined by `key`.
Carries the active `imageUrl` plus all `imageCandidates`, exactly as today.

**Grounding image** — a public-library image (fedb pair, Wikipedia lead image,
or a best-effort page image from jefit/rb100) fetched *transiently* at
generation time and handed to the image model as a pose reference. It is never
persisted; the output frame is ours.

## Data model

Changes to the `exercises/{exerciseId}` document (additive; nothing removed).

### New: the frame plan

```
# the plan — what frames this exercise needs (model-derived, admin-reviewed)
demoPlan: [                          | null   null = use legacy START/MID/END pattern
  {
    key: string                      stable slug: "start" | "bottom" | "lockout" | "p1"…
    order: int                       0-based display order
    label: string                    short UI label, e.g. "Bottom"
    caption: string                  one-line teaching cue, e.g. "Hips below parallel, chest tall"
    positionPrompt: string           the position clause fed to the image model for this frame
  }
]
planStatus: enum  NONE | PENDING | NEEDS_REVIEW | APPROVED | FAILED
```

### Changed: demo frames keyed to the plan

```
demoFrames: [
  {
    key: string                      matches a FrameSpec.key (was: phase enum)
    label: string                    denormalized from the spec for client convenience
    caption: string                  denormalized from the spec
    order: int
    imageUrl: string | null          the active frame
    imageCandidates: string[]        all generated/uploaded URLs for this frame
    phase: enum | null               DEPRECATED — retained only to read legacy docs
  }
]
```

Migration: legacy `phase` values map to `key` (`START`→`start`, `MID`→`mid`,
`END`→`end`) and synthesize a matching `demoPlan` lazily so old documents
render unchanged until regenerated.

### Surfaced (already present): the reference

```
reference: {                         | null   added in commit 140eba0
  url, source, name, score, match
  images: string[]                   grounding-only image URLs (fedb pairs today)
  groundingImages: string[]          optional — resolved/cached non-fedb grounding URLs
} | null
```

`reference` is read by the planner and the media generator. `groundingImages`
is an optional cache the planner may populate (e.g. a Wikipedia lead image or a
jefit page image) so the generator doesn't re-resolve them; it is never shown.

## Backend — Spring

### New records (`core.exercise`)

`FrameSpec(String key, int order, String label, String caption, String positionPrompt)`.
`DemoFrame` gains `key/label/caption/order` and deprecates `phase`. `Exercise`
gains `List<FrameSpec> demoPlan`, `ExerciseMediaStatus planStatus`, and a
`reference` value object; `FirestoreExerciseRepository.toExercise`/`toBody` and
`ExerciseResponse` are extended accordingly. Saves remain
`SetOptions.merge()`-safe.

### Frame planner (`integrations.exercise`, `gemini-3.5-flash`)

`ExerciseFramePlanner.plan(Exercise)`:
- Input: `name`, `movementPattern`, `mechanic`, `laterality`, `isTimed`,
  `formCues`, `description`, and — when `reference.url` is set — the **fetched,
  readable text of that page** (transient; trimmed) for grounding.
- Output: strict JSON — `{ frames: [{ key, label, caption, positionPrompt }],
  rationale }`, **1–5 frames**, clamped server-side. Enforced archetypes:
  isometric holds / steady-state → **1**; standard strength → **2**;
  multi-position skill/flow → **3–5**.
- Result is written to `demoPlan` with `planStatus = NEEDS_REVIEW`.

### Media generation — rework `GeminiExerciseMediaService`

- Iterate **`exercise.demoPlan()`** instead of `DemoPhase.values()`. If
  `demoPlan == null`, keep the current pattern-driven START/MID/END path.
- `buildPrompt` uses `frameSpec.positionPrompt` in place of
  `ExerciseMovementPhases.position(...)`; all house clauses (treatment,
  wardrobe, environment, camera, equipment, anatomy, deterministic actor)
  are unchanged.
- **Image grounding:** resolve grounding image(s) for the exercise from
  `reference` (best-effort — fedb URLs in hand; Wikipedia via Wikimedia API;
  jefit/rb100 by scraping the page image, **skipped if blocked**). Fetch the
  bytes, attach as an image `Part` on the request, and instruct the model to
  *match the body position/limb configuration of the reference while rendering
  in the house style*. Map frames to reference images by `order` (first→start
  image, last→end image, interior→nearest or none). No reference image ⇒
  text-only generation (still uses the model-derived `positionPrompt`).
- Output storage, `imageCandidates`, and the `NEEDS_REVIEW`/`FAILED`
  finalization are unchanged; GCS object names key on `frameSpec.key` instead
  of phase.

### Reference grounding resolver (`integrations.exercise`)

`GroundingImageResolver.imagesFor(reference)` → `List<byte[]>`, transient:
fedb → `reference.images`; yoga/Wikipedia → Wikimedia REST lead image;
jefit/rb100 → parse `<img>` from the page, best-effort, short timeout, swallow
failures. Nothing is persisted.

### REST endpoints (admin, `/api/admin/exercises`)

| Endpoint | Method | Purpose |
|---|---|---|
| `/{id}/plan` | GET | current `demoPlan` + `planStatus` |
| `/{id}/regenerate-plan` | POST | run the planner (optionally `{ promptOverride }`) |
| `/{id}/plan` | PUT | admin edits the plan (add/remove/reorder frames, edit caption/positionPrompt) |
| `/{id}/approve-plan` | POST | `NEEDS_REVIEW` → `APPROVED` |

Existing media endpoints (`regenerate-media`, `upload-frame`, `select-frame`,
`delete-frame`, `approve-media`) now operate on plan `key`s rather than the
phase enum.

### Backfill jobs

`BackfillExerciseFramePlanJob` (mirrors `BackfillExerciseMediaJob`): profile-
gated `CommandLineRunner`, bounded by `app.exercises.plan-backfill-limit`,
finds exercises with `planStatus = NONE` and generates plans. Catalog-wide
media regeneration runs through the existing media backfill once plans are
approved.

### Config (`application.yml` additions)

```yaml
app:
  exercises:
    plan-backfill-limit: ${app.exercises.plan-backfill-limit:50}
    plan:
      model: ${EXERCISE_PLAN_MODEL:gemini-3.5-flash}
    media:
      grounding-enabled: ${EXERCISE_MEDIA_GROUNDING_ENABLED:true}
```

## Web UI — Next.js

- **Admin** (`web/components/admin/ExerciseDemoFrames.tsx`, `RegenerateMediaModal.tsx`):
  a **plan editor** — list the `demoPlan` frames with editable label / caption /
  positionPrompt, add / remove / reorder, **Regenerate plan** and **Approve
  plan** actions; the media grid renders **N frames keyed to the plan** (not a
  fixed 3 columns), each with generate / upload / select / delete.
- **User** (`web/components/workouts/ExerciseDetailSheet.tsx`): render the N
  plan frames with their captions; drop the hardcoded 3-column assumption.
- `web/lib/types/exercise.ts`: add `FrameSpec`, extend `DemoFrame`, add
  `demoPlan`/`planStatus`/`reference` to the exercise types.

## Android

`feature-workouts/.../ExerciseDetailSheet.kt` already renders N frames in a
carousel — add the per-frame **caption** under the phase label. Wire types gain
`demoPlan` + caption fields.

## Build sequence

0. **Spike (throwaway).** Planner + grounded generation on ~5 exercises across
   archetypes (a hold, a barbell squat *with* fedb images, a single-arm press,
   a Turkish get-up, a stretch). Confirm plan sanity and that i2i grounding
   improves position accuracy **before** any schema change.
1. **Schema + migration.** `FrameSpec`, `DemoFrame` keying, `Exercise.demoPlan`/
   `planStatus`/`reference`, repository mapping, `ExerciseResponse`, legacy
   read-compat.
2. **Planner + admin endpoints + plan backfill job.**
3. **Grounded generation** rework + grounding resolver.
4. **Clients** — web plan editor + N-frame rendering; Android captions.
5. **Rollout.** Backfill plans → review → regenerate media → review → approve.
   The ~37 license-clean references and any best-effort page images ground i2i;
   the rest use model-derived position prompts.

## Out of scope

- Demo **video** (Veo) — separate track, [ADR-0009](../decisions/ADR-0009-exercise-demo-video-veo.md).
- **Storing or displaying** any reference image — grounding input only.
- Growing the reference **match set** (the 114 unmatched exercises stay
  text/pattern-only); that's a separate enrichment pass.

## Acceptance criteria

- Catalog exercises carry a reviewed `demoPlan`; holds → 1 frame, standard
  strength → 2, skill/flow → 3–5.
- Generation produces exactly the planned frames, keyed to the plan, and uses
  image-to-image grounding whenever a reference image is fetchable; output
  images are all house-style and ours.
- Web and Android render the N planned frames with captions; no client assumes
  exactly three.
- Exercises without a `demoPlan` still render via the legacy path.
- No new model or provider is introduced.
