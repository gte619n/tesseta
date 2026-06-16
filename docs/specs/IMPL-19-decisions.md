# IMPL-19 — implementation decision log

Living log of decisions made while implementing
[IMPL-19](IMPL-19-dynamic-demo-frames.md) autonomously. Each entry: the
decision, why, and any follow-up. The locked **data contract** below is the
single source of truth all components (backend, web, android) conform to.

## Scope decision — what "ready to run" means

**Decision:** "Ready to run" = all code per the spec is implemented and the
project **compiles/builds** (backend `compileJava`, web typecheck/build,
android compile), and the planner/generation/backfill endpoints + jobs exist
and are wired. We **do not execute** live Gemini planning/generation or the
catalog backfills during this implementation.

**Why:** Live generation costs money, is human-gated by spec (`planStatus`,
`mediaStatus`), and the Phase-0 spike is a validation step the operator runs.
Running it autonomously would burn budget and bypass the review gates. The
backfill jobs are implemented and runnable; invoking them is an operator step.

## Locked data contract

### Backend records (`core.exercise`)

```java
// NEW
public record FrameSpec(
    String key,            // stable slug: "start" | "bottom" | "lockout" | "p1"...
    int order,             // 0-based display order
    String label,          // short UI label, e.g. "Bottom"
    String caption,        // one-line teaching cue
    String positionPrompt  // position clause fed to the image model
) {}

// NEW value object
public record ExerciseReference(
    String url,
    String source,                 // jefit | rb100 | fedb | yoga
    String name,
    Double score,                  // nullable
    String match,                  // name | simplified
    List<String> images,           // grounding-only URLs (fedb pairs today)
    List<String> groundingImages   // optional resolved/cached grounding URLs; nullable
) {}

// CHANGED — keyed to the plan; phase deprecated (legacy read only)
public record DemoFrame(
    String key,                    // matches a FrameSpec.key
    String label,
    String caption,
    int order,
    String imageUrl,               // nullable
    List<String> imageCandidates,
    DemoPhase phase                // DEPRECATED, nullable — only to read legacy docs
) {}
```

`Exercise` gains: `List<FrameSpec> demoPlan` (nullable), `ExerciseMediaStatus
planStatus` (reuse the enum; default `NONE`), `ExerciseReference reference`
(nullable). `ExerciseResponse` mirrors these.

### Wire JSON (web `lib/types/exercise.ts`, android wire)

```ts
type FrameSpec = { key: string; order: number; label: string; caption: string; positionPrompt: string };
type DemoFrame = {
  key: string; label: string; caption: string; order: number;
  imageUrl: string | null; imageCandidates: string[];
  phase?: "START" | "MID" | "END" | null;   // legacy
};
type ExerciseReference = {
  url: string; source: string; name: string; score: number | null; match: string;
  images: string[]; groundingImages?: string[] | null;
};
// Exercise/ExerciseResponse add: demoPlan: FrameSpec[] | null; planStatus: MediaStatus; reference: ExerciseReference | null
```

### Firestore mapping

- `demoPlan`: array of maps `{ key, order, label, caption, positionPrompt }`.
- `demoFrames`: maps now carry `key/label/caption/order`; **legacy read** — if a
  frame has `phase` but no `key`, derive `key` from phase (`START`→`start`,
  `MID`→`mid`, `END`→`end`) and synthesize label/caption.
- `planStatus`: string enum; default `NONE` when absent.
- `reference`: already present (commit `140eba0`); now read into the model.
- Saves stay `SetOptions.merge()`-safe.

### Admin endpoints (`/api/admin/exercises`)

| Endpoint | Method | Body |
|---|---|---|
| `/{id}/plan` | GET | — |
| `/{id}/regenerate-plan` | POST | `{ promptOverride?: string }` |
| `/{id}/plan` | PUT | `{ frames: FrameSpec[] }` |
| `/{id}/approve-plan` | POST | — |

Existing media endpoints switch their key from `DemoPhase phase` to
`String key`: `RegenerateMediaRequest(String promptOverride, String key)`,
`FrameRequest(String key, String imageUrl)`. `key == null` on
regenerate-media means "all frames in the plan".

### Models / config

- Planner: `gemini-3.5-flash` via the existing GenAI client (env
  `EXERCISE_PLAN_MODEL`).
- Frames: `gemini-3.1-flash-image-preview` (unchanged).
- `application.yml`: `app.exercises.plan-backfill-limit` (default 50),
  `app.exercises.plan.model`, `app.exercises.media.grounding-enabled`
  (default true).
- Plan backfill job profile: `job-exercise-plan-backfill`.

## Decisions made during development

### BE-1 — structural backend layer (done; compileJava + compileTestJava green)

- **Planner port `ExerciseFramePlanner`** lives in `core.exercise` (mirrors the
  `ExerciseMediaGenerator`/`ExerciseMediaUploader` ports); injected into the
  controller as `Optional<>` so the context loads before BE-2 supplies the bean.
- **Exercise field order:** `demoPlan, planStatus, reference` inserted after
  `mediaStatus`, before `status`.
- **key↔phase bridge:** controller has `phaseForKey(String)` (reverse of
  `DemoFrame.keyForPhase`). `regenerate-media key==null` ⇒ all frames; a
  `start/mid/end` key routes to the matching `DemoPhase` for the still-legacy
  generator.
- **upload-frame/delete-frame** still route through the phase-based
  `ExerciseMediaUploader`; a non-legacy key returns **HTTP 400 ("until BE-2")**
  — BE-2 reworks these onto the plan. `upload-frame` param renamed `phase`→`key`.
- **New DTOs:** `SavePlanRequest(List<FrameSpec> frames)`, `PlanResponse(demoPlan,
  planStatus)` (PUT body / GET response — not named in the spec).
- **`recordFrame` upsert** preserves existing denormalized label/caption/order
  rather than overwriting with legacy-empty values.
- **Test fixtures + `WorkoutHistoryImporter`** updated to the new Exercise
  constructor (`null` demoPlan, `NONE` planStatus, `null` reference).

### BE-2 — behavioral backend layer (done; compileJava/compileTestJava green, context loads, *Exercise* tests pass)

- **Planner** `GeminiExerciseFramePlanner` (gemini-3.5-flash): plain
  `generateContent` + lenient JSON parse (`JsonSupport.LENIENT` + `stripFences`)
  — matches existing flash text clients (none use JSON mode). Frames clamped
  1–5, `order`=index, keys slugified+deduped, frames lacking `positionPrompt`
  dropped; empty result ⇒ throw ⇒ job marks `FAILED`. Shares the genai `Client`
  via `Optional<Client>`; throws `IllegalStateException` when `GEMINI_API_KEY`
  absent. Fetches `reference.url` text best-effort (HTML→text, ~5k cap).
- **Grounding** `GroundingImageResolver.imagesFor` → `List<RefImage>`
  (bytes+mime), transient, never throws, gated by `media.grounding-enabled`:
  fedb→`images()`; yoga→Wikimedia REST summary `originalimage`/`thumbnail`;
  jefit/rb100→`og:image` then first `<img>` (best-effort, swallow blocks).
- **Media service**: plan path attaches the reference image as the **first
  inline `Part`** with a "copy the pose only, house style otherwise" clause
  (mirrors `GeminiFoodImageGenerator.executeWithReference`); frame→ref mapping
  first/last/proportional by index. Reference bytes never persisted. Legacy
  START/MID/END path unchanged when `demoPlan` null. New
  `generateFrameAsync(exercise, key, override)` entrypoint.
- **Storage keys**: GCS object prefix changed from `phase.name()` to a sanitized
  frame key; legacy phase overloads retained (legacy object names unchanged).
- **`findByPlanStatus(NONE)`** also sweeps `findAll()` to fold in legacy docs
  with no `planStatus` field (equality query alone misses them).
- **New:** `BackfillExerciseFramePlanJob` (profile `job-exercise-plan-backfill`),
  `infra/scripts/deploy-exercise-plan-job.sh`. Operator flow: run plan backfill →
  review/approve plans in admin → run existing media backfill to generate keyed
  images. `GEMINI_API_KEY` required; grounding on by default.

### Web — client layer (done; tsc --noEmit, pnpm lint, pnpm build all green)

- **Plan editor** is a collapsible inline panel above the media grid (not a
  modal): status pill, frame count, Generate/Regenerate-plan + Edit + Approve.
  Approve disabled unless `NEEDS_REVIEW`. Edit uses a local draft with
  Save/Cancel. **Reorder** = up/down arrows (zero-dep, keyboard-accessible);
  `order` recomputed from index on mutation/save. Blank keys slugified from
  label (else `p{n}`), deduped — mirrors the backend planner.
- **N-frame rendering:** admin grid `grid-cols-2 sm:3 lg:4 xl:5`, 4:5 aspect,
  candidate strip + select/delete now keyed. User sheet picks 1/2/3 columns by
  frame count, sorts by `order`, shows captions, dashed placeholder for null
  `imageUrl`.
- **RegenerateMediaModal:** target dropdown ("All frames" + one per key) +
  optional prompt override (blank ⇒ each frame uses its own positionPrompt).
  Dropped the per-phase `getDemoPrompt` seeding (no per-key default-prompt
  endpoint); `getDemoPrompt` left exported but unwired.
- **Legacy fallback:** when `demoPlan` is null, helpers fall back to the keyed
  `demoFrames`, so pre-IMPL-19 exercises still render and admins can "Generate
  plan". The web-only `PrescriptionExercise` renders straight from `demoFrames`.

### Android — caption tweak (done; :feature-workouts:compileDebugKotlin green)

- DTO/domain live in `core-data` (Moshi reflective, no `@Json`) + `core-domain`.
  `DemoFrame`/`DemoFrameDto` gained `key/label/caption/order` (defaulted);
  `phase` made nullable for back-compat.
- `DemoViewer` sorts by `order` (source-order tiebreak), label =
  `label.ifBlank { phase ?: key }`, caption shown as a bottom-center translucent
  pill when non-blank. Carousel prev/next untouched.

## Final status — READY TO RUN (builds green across backend, web, android)

All spec code implemented and compiling. Not executed (by design): live Gemini
planning/generation and the catalog backfills.

## Interview ratification (operator review, post-implementation)

**Ratified as-built (no change):**
- Standard strength lifts → **2 frames** (start + working end); model may still
  emit more for genuinely multi-position lifts.
- `planStatus` **reuses the media-status enum**.
- Grounding fetches **all sources** incl. best-effort jefit/rb100 page scraping
  (transient, output is ours).
- Plan re-key **keeps orphaned media frames** (no auto-prune).
- **Manual two-step**: approve plan → operator runs the media job (no
  auto-trigger on plan approval).

**Changed by interview (follow-up work) — DONE:**
1. **`reference` → admin-only** ✅. `ExerciseResponse.from` returns
   `reference=null` (user-facing); new `fromAdmin` includes it; all
   `/api/admin/exercises*` responses use `fromAdmin`. Web `reference` made
   optional + admin-only consumers.
2. **Restored per-frame prompt preview** ✅. `GET /api/admin/exercises/{id}/demo-prompt?key=`
   → composed prompt (port `ExerciseMediaGenerator.promptFor(exercise, key)`);
   web Regenerate modal seeds the textarea with it for a single-key target.
3. **Generation run** — scaled from the 5-exercise spike to **25 exercises**
   (operator request). Run via an env-gated preview harness (real models +
   grounding, output to disk under
   `docs/test_reports/workout_logs/impl19_preview/`), **no production
   mutation**. 25 selected spanning all archetypes; 6 carry grounding images
   (4 fedb, 1 rb100, 1 yoga).

### Operator runbook (to actually populate frames)

1. **Backfill plans:** deploy `infra/scripts/deploy-exercise-plan-job.sh`
   (profile `job-exercise-plan-backfill`, needs `GEMINI_API_KEY`,
   `APP_EXERCISES_PLAN_BACKFILL_LIMIT` default 50). Plans land `NEEDS_REVIEW`.
2. **Review plans** in `/admin/exercises` → approve (`approve-plan`).
3. **Generate media:** run the existing `infra/scripts/deploy-exercise-media-job.sh`;
   it now generates the plan-keyed frames, image-to-image grounded where a
   reference image is fetchable (`EXERCISE_MEDIA_GROUNDING_ENABLED=true`).
4. **Review media** → approve (`approve-media`). Only `APPROVED` reaches users.
