# IMPL-14: Exercise Library

## Goal of this spec

Add a global, admin-curated **Exercise catalog** to Tesseta — the shared
vocabulary of movements that workout programs ([IMPL-15](IMPL-15-workout-programs.md))
draw from. Each exercise declares the equipment it requires (referencing the
existing global Equipment catalog), so a program can be filtered to exactly
what a given gym can execute. Each exercise carries **demo media** — a
sequence of phase stills (start / mid / end) generated in the app's house
photography style — plus an **Admin view** to generate, review, edit, replace,
and approve that media before it reaches users.

This mirrors the existing **Equipment / Drug / CatalogFood** catalog pattern:
a global, top-level Firestore collection, a contributor/approval lifecycle, an
AI media pipeline with an admin review surface, and an editable prompt.

Backend + web in this spec. Android is deferred (a future `IMPL-AND-14`,
mirroring how `IMPL-AND-12` followed `IMPL-12`).

## Decisions locked

| Decision | Choice |
|---|---|
| Catalog ownership | Global, top-level `exercises/{exerciseId}` — admin-curated, like Equipment |
| Equipment binding | Exercise references the existing global Equipment catalog by id; a gym executes an exercise iff it has the required equipment |
| Equipment alternatives | Each requirement is an "any-of" group (e.g. *barbell OR smith machine*); all groups must be satisfied |
| Demo media (v1) | Phase **stills** (2–3 per exercise) via `gemini-3.1-flash-image-preview`, using the exercise treatment in [`docs/photography-prompts.md`](../photography-prompts.md) |
| Demo media (future) | A nullable `videoUrl` is reserved on the model for true video (Google Veo) — deferred, and gated behind its own ADR (see ADR-0007 "Revisit when") |
| Human review | Generated media is never auto-published: it lands `NEEDS_REVIEW` and an admin must approve it (anatomical-correctness gate from the photography guide) |
| Media model | `gemini-3.1-flash-image-preview` (the project image model — no new model, no ADR needed) |
| Parser model | `gemini-3.5-flash` for any text parsing (catalog seed / bulk import), per the flash-only convention |
| Admin gate | `@AdminOnly` aspect + `/api/admin/exercises/**`, same as `AdminEquipmentController` |

---

## Concepts

**Exercise** — a single movement (e.g. "Barbell back squat", "Standing
calf raise", "Couch stretch", "Zone 2 row"). Globally shared, not per-user.
It names the muscles it trains, the equipment it needs, which block types it
suits, the coaching form cues, and its demo media.

**Equipment requirement** — what a gym must have to run the exercise. Each
requirement is an **any-of group** of Equipment-catalog ids: the gym satisfies
the group if it has *at least one* member. The exercise is executable at a gym
when **every** group is satisfied. A bodyweight exercise has zero groups.

> Example — "Barbell back squat" requires
> `[[barbell], [squat-rack OR power-rack], [weight-plates]]`. A gym with a
> barbell, a power rack, and plates satisfies all three groups. A hotel gym
> with only dumbbells satisfies none of them, so the squat never appears in a
> program scheduled at that hotel.

**Demo frame** — one still image for one phase of the movement (`START`,
`MID`, `END`). The app shows the frames as a sequence/loop so the user sees the
movement without video. The set of frames is the exercise's demo.

**Block-type suitability** — which kind of block an exercise belongs in
(warm-up, main, accessory, cardio, cool-down, stretch, …). IMPL-15's generator
uses this so it never drops a deadlift into the stretch block.

---

## Data model

### Firestore collection

Global and top-level — the same shape as the Equipment catalog
(`equipment/{equipmentId}`), not user-scoped:

```
exercises/{exerciseId}
```

Demo frames and equipment requirements are **embedded** in the document (small,
bounded lists) rather than subcollections — one read returns a fully renderable
exercise, matching the persistence guidance of nesting bounded data instead of
sub-subcollections.

### Exercise document

```
exerciseId: string
name: string
nameLower: string                  search index (lower-cased name)
aliases: string[]                  e.g. ["back squat", "high-bar squat"]
movementPattern: enum  SQUAT | HINGE | LUNGE | PUSH_HORIZONTAL |
                       PUSH_VERTICAL | PULL_HORIZONTAL | PULL_VERTICAL |
                       CARRY | CORE | CARDIO | MOBILITY | STRETCH | OTHER
primaryMuscles: string[]           e.g. ["quadriceps", "glutes"]
secondaryMuscles: string[]
laterality: enum  BILATERAL | UNILATERAL
mechanic: enum  COMPOUND | ISOLATION
description: string
formCues: string[]                 short coaching cues, shown on the card

# equipment binding — see "Equipment requirement" above
requiredEquipment: [               every group must be satisfied; [] = bodyweight
  { anyOf: string[] }              equipmentIds from the global Equipment catalog
]

# which blocks this exercise can populate (IMPL-15 generator honors this)
suitableBlockTypes: enum[]  WARMUP | MOBILITY | CARDIO | MAIN | ACCESSORY |
                            CORE | COOLDOWN | STRETCH

# default prescription hints the generator may use as a starting point
defaultRepRange: { min: int, max: int } | null
isTimed: boolean                   true for cardio/holds (duration, not reps)

# demo media
demoFrames: [
  {
    phase: enum  START | MID | END
    imageUrl: string | null        the active frame for this phase
    imageCandidates: string[]      all generated/uploaded URLs for this phase
  }
]
videoUrl: string | null            RESERVED for future Veo video; always null in v1
demoPromptOverride: string | null  admin-edited prompt; null = use the built default
mediaStatus: enum  NONE | PENDING | NEEDS_REVIEW | APPROVED | FAILED

# catalog lifecycle — mirrors Equipment
status: enum  DRAFT | PUBLISHED | ARCHIVED
contributorId: string | null
createdAt: timestamp
updatedAt: timestamp
aliasOfExerciseId: string | null   merge pointer, like Equipment.aliasOfEquipmentId
```

`mediaStatus` lifecycle: `NONE` → admin triggers generation → `PENDING` →
generator finishes → `NEEDS_REVIEW` (or `FAILED`) → admin approves →
`APPROVED`. Only `PUBLISHED` + `APPROVED` exercises are offered to the IMPL-15
generator and shown to users; `DRAFT` / `NEEDS_REVIEW` are admin-only.

### Why reference the Equipment catalog by id

Locations already store `equipmentIds: string[]` pointing into the same global
`equipment/{equipmentId}` catalog (see `Location`). Binding exercises to those
same ids means gym-vs-exercise matching is a pure set operation with no fuzzy
category logic:

```
executableAt(location, exercise) :=
    every group g in exercise.requiredEquipment has
        (g.anyOf ∩ location.equipmentIds) ≠ ∅
```

---

## Backend — Spring

### Module placement

New `exercise` package across the existing modules, following Equipment:

- `core/exercise` — `Exercise` record, enums, `ExerciseRepository` interface,
  `ExerciseService`, the `ExerciseMediaGenerator` / `ExerciseMediaUploader`
  ports, and the `ExerciseAvailabilityService`.
- `persistence/exercise` — `FirestoreExerciseRepository`.
- `api/exercise` + `api/admin` — public read controller and the admin
  controller.
- `integrations/exercise` — `GeminiExerciseMediaGenerator`,
  `ExerciseMediaStorage` (GCS).

### Availability service (the seam IMPL-15 depends on)

```java
public interface ExerciseAvailabilityService {
    // catalog exercises executable at the given gym, given its equipmentIds
    List<Exercise> executableAt(String locationId);
    // does this specific exercise work at this gym?
    boolean isExecutableAt(String exerciseId, String locationId);
}
```

One implementation reads the `Location` (its `equipmentIds`) and the published
exercise catalog and applies the set rule above. This is the *only* place the
equipment constraint is computed; both the IMPL-15 generator prompt and the
program validator call it.

### ExerciseMediaGenerator port

Mirrors `EquipmentImageGenerator` exactly — fire-and-forget, updates the
exercise's `demoFrames` / `mediaStatus` on completion, and exposes the default
prompt so admins can seed an editable field:

```java
public interface ExerciseMediaGenerator {
    // regenerate all phases (START/MID/END) for the exercise
    CompletableFuture<Void> generateDemoAsync(Exercise exercise);
    // regenerate, using promptOverride verbatim instead of the built default
    CompletableFuture<Void> generateDemoAsync(Exercise exercise, String promptOverride);
    // regenerate a single phase only
    CompletableFuture<Void> generatePhaseAsync(Exercise exercise, DemoPhase phase, String promptOverride);
    // the default prompt this generator would produce for a given phase
    String defaultPrompt(Exercise exercise, DemoPhase phase);
}
```

On completion the generator sets `mediaStatus = NEEDS_REVIEW` (never
`APPROVED`) — the human-review gate from the photography guide is mandatory for
exercise media because a wrong joint angle teaches an injurious movement.

### REST endpoints

Public (authenticated) read:

```
GET  /api/exercises                       list PUBLISHED+APPROVED (filter: ?pattern=&block=&muscle=)
GET  /api/exercises/{id}                   single exercise
GET  /api/exercises/available?locationId=  executable at a gym (delegates to ExerciseAvailabilityService)
```

Admin (`@AdminOnly`, mirrors `AdminEquipmentController`):

```
GET    /api/admin/exercises/catalog                list all (any status)
GET    /api/admin/exercises/review                 mediaStatus = NEEDS_REVIEW
POST   /api/admin/exercises                         create (DRAFT)
PATCH  /api/admin/exercises/{id}                    edit metadata / requirements
POST   /api/admin/exercises/{id}/publish            DRAFT → PUBLISHED
POST   /api/admin/exercises/{id}/archive            → ARCHIVED

GET    /api/admin/exercises/{id}/demo-prompt?phase=START   built default prompt for a phase
POST   /api/admin/exercises/{id}/regenerate-media          all phases; body: { promptOverride? }
POST   /api/admin/exercises/{id}/regenerate-phase          one phase; body: { phase, promptOverride? }
POST   /api/admin/exercises/{id}/upload-media              multipart; body: file + phase
POST   /api/admin/exercises/{id}/select-frame              { phase, imageUrl } pick active candidate
POST   /api/admin/exercises/{id}/delete-frame              { phase, imageUrl }
POST   /api/admin/exercises/{id}/approve-media             NEEDS_REVIEW → APPROVED
POST   /api/admin/exercises/{sourceId}/merge-into/{targetId}
```

### Media generation — Gemini

`GeminiExerciseMediaGenerator` uses `gemini-3.1-flash-image-preview` (the
project image model — **no new model, no ADR**) and builds each phase prompt
**verbatim** from [`docs/photography-prompts.md`](../photography-prompts.md)
"Category 1 — Exercise instruction photography":

```
[SHARED TREATMENT BLOCK]      # oatmeal F0EBE0, soft daylight, matte, editorial
[MODEL CLAUSE]                # single athletic person, mid-30s, neutral, calm
[WARDROBE CLAUSE]             # heather-gray fitted athletic clothing, no logos
[ENVIRONMENT CLAUSE]          # oatmeal seamless backdrop, matte rubber flooring
[CAMERA CLAUSE]               # true side-profile, full body + equipment in frame
The person is performing: {exercise.name}, {phase clause}, {key form cues}.
```

The shared treatment block is copied verbatim so exercise, equipment, and
medication imagery read as one coherent product — this is exactly why
`photography-prompts.md` forbids editing the shared block per image. The
per-phase clause (`START` / `MID` / `END`) and the negative prompt come from
the same file. Aspect ratio `4:5` (vertical body framing) per the guide's
global parameters.

> **Aesthetic match for the demo (the user's explicit ask):** the demo prompt
> is *derived from* the same house treatment that already governs equipment and
> medication imagery, so the exercise library cannot drift from the rest of the
> app. When video lands later (Veo, behind its own ADR), the video prompt must
> extend this same treatment block, not invent a new look.

### Storage

New GCS bucket `health-fitness-160-exercise-media`, versioned object naming
(`exercises/{exerciseId}/{phase}_{timestamp}.webp`) with
`Cache-Control: public, max-age=31536000, immutable`, and best-effort
delete-old-on-regenerate — identical to `EquipmentImageStorage`.

### Config (proposed `application.yml` additions)

```yaml
app:
  exercises:
    bucket: ${EXERCISE_MEDIA_BUCKET:health-fitness-160-exercise-media}
    media:
      enabled: ${EXERCISE_MEDIA_ENABLED:true}
      model: ${EXERCISE_MEDIA_MODEL:gemini-3.1-flash-image-preview}
    gemini-api-key: ${GEMINI_API_KEY:}
    parser-model: ${GEMINI_MODEL:gemini-3.5-flash}
```

---

## Web UI — Next.js

### Admin: Exercise catalog + review (`/admin/exercises`)

Mirrors `/admin/equipment` (catalog + review tabs), reusing the admin
component patterns (`AdminEquipmentClient`, `RegenerateImageModal`,
`ImageLightbox`, the modal-backdrop trio from `web/CLAUDE.md`).

- **Review tab** — exercises with `mediaStatus = NEEDS_REVIEW`. Each row shows
  the three phase frames side by side in the house style; a lightbox enlarges
  each frame. Actions: **Approve media**, **Regenerate** (opens a modal with
  the editable default prompt seeded from `GET .../demo-prompt`), **Regenerate
  one phase**, **Upload** a manual frame, **Select / Delete** a candidate. The
  guide's anatomical-review warning is shown inline so the reviewer knows to
  check joint angles and grip before approving.
- **Catalog tab** — all exercises with status + media-status pills, search by
  name/muscle/pattern, edit metadata + equipment requirements (an
  equipment-picker that searches the Equipment catalog and builds the any-of
  groups), publish/archive, and merge duplicates.

All styled with the existing tokens (oatmeal canvas `#F0EBE0`, olive accent
`#5C7A2E`, `caps-mono` labels, `rounded-[14px]` cards). Reads are pre-fetched
server-side; mutations are server actions passed to client components as
callback props (`web/CLAUDE.md` server-action-as-prop rule), via a new
`lib/exercise-admin-api.ts`.

### User-facing

No standalone user page in this spec — exercises surface inside IMPL-15's
program views (a tappable exercise opens an **Exercise detail** sheet showing
the phase-still sequence + form cues). The read endpoints and the
`ExerciseCard` / `ExerciseDetailSheet` components ship here so IMPL-15 can
consume them.

---

## Seeding the catalog

The catalog starts empty. v1 seeding is **admin-authored** through the create
endpoint plus media generation — start with a core set (~40–60 movements:
the major barbell/dumbbell/machine lifts, common accessories, a cardio set, a
mobility/stretch set) so IMPL-15 has enough to build real programs. A
`gemini-3.5-flash` bulk-import/parse path (paste a list → draft exercises with
guessed muscles/patterns/equipment for admin review) may follow as a separate
plan doc, exactly as `IMPL-GYM-002` did for equipment. Not required for IMPL-15
to function.

---

## Build sequence

1. `core/exercise`: `Exercise` record + enums + `ExerciseRepository` interface
   + `ExerciseService` (CRUD, status transitions, merge).
2. `persistence/exercise`: `FirestoreExerciseRepository`.
3. `ExerciseAvailabilityService` + tests (the equipment set rule).
4. Public read controller (`/api/exercises*`) + admin controller
   (`/api/admin/exercises*`).
5. `integrations/exercise`: `ExerciseMediaStorage` + `GeminiExerciseMediaGenerator`
   (prompt built from `photography-prompts.md`), bucket + config.
6. Web: `/admin/exercises` catalog + review, equipment-requirement picker,
   regenerate/upload/approve flows, `ExerciseCard` / `ExerciseDetailSheet`.
7. Seed the initial ~40–60 exercises and run them through generate → review →
   approve.

## Out of scope for IMPL-14

- True video demos (Veo). `videoUrl` is reserved on the model but always null;
  introducing a video model requires its own ADR (ADR-0007 "Revisit when").
- Android exercise UI (future `IMPL-AND-14`).
- A `gemini-3.5-flash` bulk-import pipeline (a later plan doc, like IMPL-GYM-002).
- User-submitted exercises / community contributions (admin-authored only in v1).
- Per-user exercise customization or private exercises.

## Acceptance criteria

- An admin can create an exercise, set its equipment requirements against the
  real Equipment catalog (including an any-of alternative group), and publish
  it.
- `GET /api/exercises/available?locationId=` returns exactly the published
  exercises whose every requirement group is satisfied by that gym's
  `equipmentIds`; a bodyweight exercise (no requirements) is returned for every
  gym; an exercise needing a barbell is absent for a dumbbell-only gym.
- Triggering media generation produces START/MID/END phase stills in the house
  oatmeal/olive treatment, the exercise lands `NEEDS_REVIEW`, and it is **not**
  offered to users or the IMPL-15 generator until an admin approves it.
- An admin can edit the demo prompt, regenerate a single phase, upload a
  manual frame, select the active candidate, and approve — each reflected on
  the exercise.
- Merging a duplicate exercise into a canonical one hides the alias from
  catalog/available listings and resolves navigation through the pointer,
  matching Equipment merge behavior.
