# IMPL-FIXPACK-01 — Nutrition & Workout Fix Pack

Status: **Plan / not started** · Owner: Evan Ruff · Created: 2026-09-16
Branch: `bug-fixes` (single branch, phased) · Base: `origin/main` (`ddd4db11`)

Four reported issues from a live session, spanning nutrition (composite-meal
editing) and workouts (guided timed-exercise flow). This document is the result
of a code-level investigation; each phase lists the **diagnosed root cause**, the
**fix**, the **files**, the **tests (technical + functional)**, and an
**agent-verifiable Definition of Done** so nothing is marked complete on
assertion alone.

The four items are largely independent. Delivery decision: **one branch,
merged/pushed as each phase's DoD is met** (see Decision D-DELIV). No strong
priority ordering — sequenced below for engineering efficiency (nutrition data
model first, then the workout flow), but any phase may ship first.

---

## Reported issues (verbatim)

1. **Ingredient qty edit doesn't update the day.** "When going into the meal
   editor and adjusting the amounts of the individual ingredients, the meal
   totals applied to the day do not change. If I go from Qty 1 to Qty 0.5 for the
   mixed greens, it does not update the meal nutrition facts for that day."
2. **No way to remove an ingredient.** "Occasionally the photograph will capture
   things in the background and add it to the meal when it is not part of the
   meal. Remove it and update the title if necessary."
3. **Runaway timer on back-to-back timed exercises.** "I have two timed exercises
   back to back. When I completed the last working set of Exercise A, it
   immediately jumped into the next exercise. The timer continued to run and I had
   no way to reset or stop it. It skipped the rest period entirely."
4. **No effort signal for timed exercises.** "There is no RIR for hold exercises.
   RIR is not appropriate, but we need some way to determine if I can do more,
   less, or the same for a timed exercise. Maybe a simple icon for less/same/more."

---

## Decision log (from stakeholder interview, 2026-09-16)

| ID | Decision |
|----|----------|
| **D-DELIV** | Single `bug-fixes` branch, merged/pushed phase by phase as each DoD is met. |
| **D-PRIO**  | No strong priority; all roughly equal. Sequence by engineering efficiency. |
| **D-RM-MECH** | Remove ingredient = **direct immediate delete** (row trash/X → instant, backend re-sums). New DELETE endpoint; reuse existing composite re-sum math. |
| **D-RM-UNDO** | **Snackbar Undo** after delete (restores the ingredient + re-sums). No confirm dialog. |
| **D-RM-TITLE** | Title update = **AI re-title, opt-in** (a button re-generates the title from remaining ingredients + photo). Not automatic. |
| **D-RM-PHOTO** | Finished-meal image = **regenerate opt-in** (reuse Adjust image-regen path). Original kept until user asks. |
| **D-RM-SAVED** | Removal affects **only this logged entry**. Never touch a save-as-meal catalog copy. |
| **D-QTY-SCOPE** | Bug observed on **Android** only (web untested by owner). Fix + regression-test Android; smoke-check web is unaffected. |
| **D-TEST-NUT** | Nutrition proof = **day-total functional tests** (assert the *day's* totals change after a qty edit / removal, end-to-end through the offline mirror), not just entry-level. |
| **D-TMR-TRANS** | Last-set transition = **auto-advance, but the get-ready must be fully controllable and never orphaned**. |
| **D-TMR-REST** | Between back-to-back timed exercises, honor the **prescribed rest** (visible, controllable countdown) before the next get-ready. Fixes "skipped the rest entirely." |
| **D-TMR-CTRL** | Mandatory control on any on-screen countdown: **Skip** (begin the hold / next immediately). Stop/Reset/±time not required for v1. |
| **D-TF-DRIVE** | more/same/less signal: **capture now, progress later.** Log + show in history; do NOT change next-session targets yet (progression engine change deferred to a later phase). |
| **D-TF-WHEN** | Prompt on the **final set only** (mirrors the existing rep RIR final-set gate). |
| **D-TF-GATE** | **Required gate on the final set** — must be answered before the timed exercise can complete. |
| **D-TF-UI** | **Three icons** (down / equals / up), matching the "simple icon" ask. |
| **D-TF-MEANING** | Semantics = **capability**: up = "could do MORE" (too easy), equals = "about right", down = "could do LESS" (too hard). Maps cleanly to future duration progression (more → lengthen). |
| **D-TF-SCOPE** | Applies to **all timed exercises** (anything with `durationSeconds`: planks, dead hangs, timed carries, timed cardio). |

---

## How to read status in this document

Each phase has a **Status line** and a **Tracking checklist** with these states:

- `[ ]` not started · `[~]` in progress · `[x]` done & verified
- Four tracked dimensions per phase: **Implemented**, **Tested (technical)**,
  **Tested (functional)**, **Pushed**.

A phase is **DONE** only when all four are `[x]` and the phase's
**Agent Verification** block has been executed with the recorded result pasted
in. Do not mark a dimension `[x]` on assertion — paste the command + output.

Global roll-up (update as phases complete):

| Phase | Implemented | Tested (tech) | Tested (func) | Pushed |
|-------|:-----------:|:-------------:|:-------------:|:------:|
| 1 — Ingredient qty → day totals | [x] | [x] | [x] | [x] |
| 2 — Remove ingredient (+undo, opt-in re-title/re-image) | [x] | [x] | [x] | [x] |
| 3 — Timed-exercise timer/rest fix | [x] | [x] | [x] | [x] |
| 4 — Timed more/same/less capture | [x] | [x] | [x] | [x] |

---

## Phase 1 — Ingredient qty edit must update the day total

**Status:** Not started
**Issue:** #1 · **Decisions:** D-QTY-SCOPE, D-TEST-NUT

### Diagnosed context (current code)

The per-ingredient path and the whole-meal-portion path are **different**:

- Whole-meal portion edits go through `NutritionRepository.patchEntry()` and were
  fixed in `f6da37ee` ("re-scale composite macros on local portion edit") — that
  fix recomputes `compositeTotal(ingredients, portion)` for a portion-only local
  patch. **This is already on main and is NOT the reported bug.**
- Per-ingredient qty edits go through a **different** call:
  `NutritionTodayViewModel.saveCompositeMeal()`
  (`android/feature-nutrition/.../NutritionTodayViewModel.kt:250-288`) →
  `repository.updateIngredient()`
  (`android/core-data/.../NutritionRepository.kt:800-811`), which is deliberately
  network-first: `api.updateIngredient(...)` then `fillDayAndSignal(date)`
  (comment at :806 — "keep it on the network and refresh the mirror").
- The most recent commit is **the Offline-first + performance refactor (#258,
  `ddd4db11`)** — the prime suspect for regressing this network-first path.

### Step 1 — Reproduce & pinpoint (do first, do not skip)

Reproduce Qty 1 → 0.5 on one ingredient of a composite entry and capture where
the chain breaks. Leading hypotheses to confirm/eliminate:

1. **Change not detected** — `saveCompositeMeal:266`
   `if ((ing.quantity ?: 1.0) != newQty)` never fires because the sheet seeds
   `quantities` with values that don't compare equal to what it later reads
   (float identity / string round-trip in `IngredientsSheet`).
2. **Network call skipped/failing under offline-first** — after #258,
   `api.updateIngredient` is short-circuited by a kill-switch/mirror gate, throws
   offline, or `fillDayAndSignal` no longer repopulates the day the way
   `repository.day(date)` (called at `:280`) reads it → the ViewModel re-reads a
   stale mirror.
3. **Server re-scale produces no change** — ingredient lacks `macrosPer100g` /
   `servingGrams` baseline, so `CompositeIngredient.withPortion(...)` can't
   re-scale from a per-100g base and returns the frozen snapshot.
4. **Day rollup not recomputed / not re-read** — entry updates but
   `recomputeDay` or the mirror day-read doesn't reflect it.

Record the confirmed root cause here before writing the fix:

> **Confirmed root cause:** _(fill in)_

### Step 2 — Fix

Fix the confirmed break so a per-ingredient qty change deterministically flows to
the day total, **both online and offline** (offline-first must not silently drop
the re-portion). Mirror the entry-total re-sum locally if the offline path needs
it (the local `compositeTotal(ingredients, portion)` helper already exists in
`NutritionRepository`, used by the `f6da37ee` fix — reuse it, do not duplicate).

### Step 3 — Guard the web path (D-QTY-SCOPE)

Web PATCHes ingredients via REST where the backend re-scales; confirm by
inspection (or a quick web test) that the web meal editor already reflects
ingredient qty edits in day totals. If it does, note it; if not, file a follow-up
(do not expand this phase's scope silently — `log` the gap).

### Files (expected)

- `android/feature-nutrition/.../NutritionTodayViewModel.kt` (`saveCompositeMeal`)
- `android/feature-nutrition/.../IngredientsSheet.kt` (qty seeding / onSave)
- `android/core-data/.../NutritionRepository.kt` (`updateIngredient`, mirror refresh, `compositeTotal`)
- (only if backend is implicated) `backend/.../nutrition/NutritionService.java` (`updateIngredient`, `compositeTotal`, `recomputeDay`)

### Testing

**Technical (unit):**
- Entry-level: patching one ingredient's `quantity` from 1.0 → 0.5 re-sums the
  entry macros to the correct partial total (extend
  `NutritionRepositoryLogMealTest` alongside the existing portion test).
- Offline path: with the network unavailable / kill-switch on, a qty edit still
  updates the mirrored entry + day (no silent drop).

**Functional (day-total, per D-TEST-NUT):**
- End-to-end through the mirror: seed a day with a composite entry, edit one
  ingredient's qty, then read `repository.day(date)` and assert the **day's**
  total kcal/macros changed by exactly the expected delta (not just the entry).
- This test must FAIL on current `main`/pre-fix code and PASS after — prove the
  red first.

### Definition of Done (Phase 1)

- [ ] Root cause recorded above with the specific file:line.
- [ ] Qty edit changes the entry total AND the day total, online and offline.
- [ ] New day-total functional test proven red-before/green-after.
- [ ] Web path confirmed unaffected (or follow-up filed).

### Agent Verification (paste command + output before marking done)

```
# Unit + functional nutrition tests
./gradlew :core-data:testDebugUnitTest --tests '*NutritionRepository*'
./gradlew :feature-nutrition:testDebugUnitTest --tests '*NutritionToday*'
```
- [ ] Above pass.
- [ ] Manual in-app (run skill): log a composite meal, set an ingredient to 0.5×,
      reopen the day — the day header total reflects the reduced macros. Screenshot
      or note observed before/after totals here: _(fill in)_

---

## Phase 2 — Remove an ingredient from a logged meal

**Status:** Not started
**Issue:** #2 · **Decisions:** D-RM-MECH, D-RM-UNDO, D-RM-TITLE, D-RM-PHOTO, D-RM-SAVED, D-TEST-NUT

### Design (per decisions)

Direct, immediate delete of a single ingredient row (D-RM-MECH), with a snackbar
Undo (D-RM-UNDO). After removal, the entry re-sums from the remaining ingredients
and the day rolls up. Two **opt-in** follow-ups surfaced after a delete:
**AI re-title** (D-RM-TITLE) and **regenerate image** (D-RM-PHOTO). The save-as-meal
catalog copy is never touched (D-RM-SAVED).

### Step 1 — Backend: delete-ingredient endpoint

Add `DELETE /api/me/nutrition/{date}/entries/{entryId}/ingredients/{index}`
(sibling to the existing PATCH `.../ingredients/{index}` in
`NutritionController.java:1023`). Service removes the ingredient at `index`,
recomputes `compositeTotal(remaining, portion)` and the day rollup, saves, and
fires `syncNotifier.changed(... "nutritionDays/entries")`. Guard the last-remaining
ingredient (a composite with 0 ingredients is invalid — either forbid, or convert
to a single/empty state — **decide and document**; default: forbid removing the
last ingredient, return 409).

### Step 2 — Android: repository + API

Add `removeIngredient(date, entryId, index)` to `NutritionApi` +
`NutritionRepository`, mirroring `updateIngredient` (network-first + mirror
refresh), and — matching whatever Phase 1 establishes — ensure the **offline**
path re-sums the mirrored entry/day locally so removal is not lost offline.

### Step 3 — Android: UI (IngredientsSheet)

- Add a delete affordance (trash/X) to each `IngredientCard`
  (`IngredientsSheet.kt`).
- On delete: optimistic remove from the local `quantities`/ingredient list, call
  repository, show **snackbar "Ingredient removed · Undo"**; Undo restores the
  ingredient (re-add via the composite path) and re-sums.
- After a successful delete, surface the two opt-in actions:
  - **"Re-title with AI"** — reuse the Adjust-with-AI re-title path
    (`MealAdjustmentService` title regen / `composeMealName`) so the new name is
    generated from remaining ingredients (+ photo).
  - **"Regenerate photo"** — reuse the Adjust image-regen path.
  Both are explicit taps, never automatic.

### Files (expected)

- `backend/.../nutrition/NutritionController.java` (new DELETE mapping)
- `backend/.../nutrition/NutritionService.java` (`removeIngredient`, re-sum, day recompute)
- `android/core-data/.../nutrition/NutritionApi.kt`, `NutritionRepository.kt`
- `android/feature-nutrition/.../IngredientsSheet.kt`, `IngredientCard`, `NutritionTodayViewModel.kt`
- Reuse: `MealAdjustmentService` (re-title + image regen), `AdjustWithAi.kt` wiring

### Testing

**Technical (unit):**
- Backend: removing an ingredient re-sums the entry and the day; removing the last
  ingredient returns the documented guard (409/forbid).
- Android: repository `removeIngredient` updates mirror online + offline; Undo
  restores the exact prior state.

**Functional (day-total, per D-TEST-NUT):**
- Seed a composite with a stray background item; remove it; assert the **day**
  total drops by exactly that item's contribution.
- Undo restores the day total to the pre-delete value.
- Opt-in re-title produces a name that excludes the removed item; opt-in
  regenerate replaces the image ref. (Mock the AI/image services; assert the
  request is made and the entry updates, not the model output.)

### Definition of Done (Phase 2)

- [ ] Single-tap delete removes an ingredient; entry + day re-sum correctly.
- [ ] Snackbar Undo restores the ingredient and totals.
- [ ] Opt-in AI re-title and opt-in image regen wired (explicit taps only).
- [ ] Save-as-meal catalog copy untouched (asserted).
- [ ] Last-ingredient guard behavior documented + tested.
- [ ] Day-total functional tests proven red-before/green-after.

### Agent Verification (paste command + output before marking done)

```
./gradlew :backend:test --tests '*Nutrition*Ingredient*' --tests '*NutritionService*'
./gradlew :core-data:testDebugUnitTest --tests '*NutritionRepository*'
./gradlew :feature-nutrition:testDebugUnitTest --tests '*Nutrition*'
```
- [ ] Above pass.
- [ ] Manual in-app: log a composite meal, delete one ingredient → day total drops;
      tap Undo → restored; tap Re-title → name updated. Note observed values: _(fill in)_

---

## Phase 3 — Timed-exercise timer & rest fix (back-to-back holds)

**Status:** Not started
**Issue:** #3 · **Decisions:** D-TMR-TRANS, D-TMR-REST, D-TMR-CTRL

### Diagnosed root cause (current code)

The runaway timer is a **race at the timed-set → next-exercise transition**:

1. `logTimedSet()`
   (`android/feature-workouts/.../WorkoutSessionViewModel.kt:231-243`) logs the
   hold and calls `startRestOrComplete(..., startRest = false)` — **timed sets
   deliberately start no rest** (:227-229 comment). This is the "skipped the rest
   entirely" behavior (D-TMR-REST fixes it).
2. `startRestOrComplete` (:263-276): on completion it calls `timers.clearRest()`
   (:271). Meanwhile the screen's auto-advance
   (`WorkoutSessionScreen.kt:533-566`) sets `autoStartStep = nextIndex` when the
   current AND next exercise are both timed, and the next hold card's
   `LaunchedEffect(autoStart)` (~:1946) calls `onStartGetReady(...)` on the
   **same** `WorkoutSessionTimers` singleton.
3. The get-ready countdown is started, then the page advances and the previous
   hold card unmounts — its Pause/controls go with it — while the singleton timer
   (and its foreground-service notification) keeps ticking. `clearRest()` and the
   just-started get-ready race, leaving an **orphaned countdown with no mounted
   controls**: "the timer continued to run and I had no way to reset or stop it."

The prior `rest-timer-dual-state-gate` work (`31c80438`, routing get-ready through
the shared timer) unified UI/notification state but did not account for this
completion/transition race.

### Fix (per decisions)

1. **Insert the prescribed rest between back-to-back timed exercises (D-TMR-REST).**
   When a timed set completes and it is NOT the session's end, honor the
   exercise's `restSeconds` as a real, visible rest countdown before the next
   exercise's get-ready. Change the `startRest = false` assumption for the
   inter-exercise case (a rest overlay should no longer "fight" the flow — it is
   the recovery window the user expects).
2. **Never orphan a countdown (D-TMR-CTRL).** Any on-screen rest/get-ready
   countdown must always render a **Skip** control, and the control must remain
   mounted/valid across the page advance (drive it from the shared timer flow +
   the destination card, not the unmounting source card). Skipping begins the hold
   (or the next exercise) immediately.
3. **Fix the clear/start race (D-TMR-TRANS).** Auto-advance is kept, but the
   completion path and the get-ready start must not both touch the singleton in a
   racing order. `clearRest()` must not cancel a countdown that belongs to the
   next (still-in-progress) exercise. Ensure completion detection
   (`draft.isComplete`) only clears timers when the session is genuinely finished,
   and the get-ready for a legitimately-next exercise is owned by the destination
   card.

### Files (expected)

- `android/feature-workouts/.../session/WorkoutSessionViewModel.kt` (`logTimedSet`, `startRestOrComplete`)
- `android/feature-workouts/.../session/WorkoutSessionScreen.kt` (auto-advance :533-566, HoldTimer/get-ready ~:1878-2106, TimedSetsSection :1722-1804 — add Skip, keep controls mounted)
- `android/core-data/.../session/WorkoutSessionTimers.kt` (rest/get-ready ownership; avoid cross-exercise clear)
- `android/feature-workouts/.../session/WorkoutSessionService.kt` + notification content (keep notification in sync; no stale ticking)

### Testing

**Technical (unit / ViewModel):**
- `startRestOrComplete`: completing a timed set that is NOT the last exercise
  starts the prescribed rest (not `clearRest`); completing the genuinely-last set
  clears timers and opens the finish summary.
- Timer ownership: starting exercise B's get-ready does not get cancelled by
  exercise A's completion handler.

**Functional (state-machine / instrumented):**
- Two back-to-back timed exercises: completing A's last set → a **rest** countdown
  appears with a working **Skip**; after rest, B's get-ready appears (also
  Skippable); no countdown is ever running without a visible control.
- Skip during rest jumps straight to B's get-ready/hold. Skip during get-ready
  starts the hold.
- Regression: a timed exercise that IS the last of the session still auto-opens
  the finish summary with the chime and no dangling timer.
- Notification/foreground-service state matches the on-screen countdown (no
  orphaned notification chronometer) — assert via the notification-content
  builder test.

### Definition of Done (Phase 3)

- [ ] Root cause + race confirmed with file:line in this doc.
- [ ] Prescribed rest shown between back-to-back timed exercises.
- [ ] Every countdown always shows a working Skip; controls survive the auto-advance.
- [ ] No orphaned/uncontrollable timer in any timed→timed transition.
- [ ] Last-set-of-session still finishes cleanly (regression test).

### Agent Verification (paste command + output before marking done)

```
./gradlew :feature-workouts:testDebugUnitTest --tests '*WorkoutSession*' --tests '*HoldTimer*' --tests '*Timed*'
./gradlew :core-data:testDebugUnitTest --tests '*WorkoutSessionTimers*'
```
- [ ] Above pass.
- [ ] Manual in-app (run skill): build/enter a session with two consecutive timed
      exercises; complete A's last set; confirm a controllable rest → get-ready,
      Skip works at each stage, nothing runs uncontrolled. Note observed flow: _(fill in)_

---

## Phase 4 — "Could do more / same / less" for timed exercises (capture)

**Status:** Not started
**Issue:** #4 · **Decisions:** D-TF-DRIVE, D-TF-WHEN, D-TF-GATE, D-TF-UI, D-TF-MEANING, D-TF-SCOPE

### Design (per decisions)

Capture a 3-way **capability** signal on the **final set** of every timed
exercise, as a **required gate** (mirrors the rep RIR final-set gate), presented
as **three icons** (down = could do less / equals = about right / up = could do
more). **Capture only** for v1 — it is stored and shown in history but does NOT
change next-session targets yet (the progression engine still skips timed
exercises — `SessionLoop.java:91`). Wiring it into duration progression is
explicitly a **later phase** (see Phase 5 stub).

### Step 1 — Data model (new field end-to-end)

Add a nullable timed-effort field to the logged-set model on all layers. Suggested
name: `timedEffort` with values `LESS | SAME | MORE` (a small enum), kept distinct
from `rir`/`rirSource` so the two never collide.

- Backend: `core/workoutprogram/LoggedSet.java` (new nullable field +
  backward-compat constructor, like the existing `rir`/`durationSeconds` adds).
- Android domain: `domain/workouts/program/WorkoutProgram.kt` (`LoggedSet`).
- Wire DTO: `data/workouts/program/WorkoutProgramDto.kt` (`LoggedSetDto`).
- Room: no schema change (session persisted as Moshi JSON in
  `WorkoutSessionDraftEntity.loggedJson`) — verify the adapter round-trips the new
  field and tolerates null (old drafts).

### Step 2 — Capture in the ViewModel

Extend `logTimedSet()` to accept the effort value and persist it on the
`LoggedSet` (only meaningful on the final set; earlier sets stay null).

### Step 3 — UI (final-set gate + three icons)

- In `TimedSetsSection`/`CompletedTimedRow` (`WorkoutSessionScreen.kt:1722-1804`),
  render a three-icon selector on the **final** timed set.
- **Gate:** the exercise cannot be marked complete (and the finish/auto-advance
  from Phase 3 must not proceed) until an icon is chosen on the final timed set —
  the timed analogue of the required-RIR gate (`ActiveRepCard requireRir`).
  Coordinate with Phase 3 so the gate is satisfied *before* the auto-advance/rest.
- Semantics fixed by D-TF-MEANING: up = MORE (too easy) … down = LESS (too hard).

### Step 4 — History display

Show the captured signal in the completed timed row / session history (e.g. the
chosen icon next to the duration). No progression math.

### Non-goals (v1)

- No change to `SessionLoop`/`ProgressionEngine` (timed still skipped, D-TF-DRIVE).
- No duration auto-adjust. Captured data is the input for a future phase.

### Files (expected)

- `backend/.../core/workoutprogram/LoggedSet.java` (+ any completion mapper that copies fields)
- `android/core-domain/.../workouts/program/WorkoutProgram.kt`
- `android/core-data/.../workouts/program/WorkoutProgramDto.kt`
- `android/feature-workouts/.../session/WorkoutSessionViewModel.kt` (`logTimedSet`)
- `android/feature-workouts/.../session/WorkoutSessionScreen.kt` (three-icon selector, final-set gate, history row)

### Testing

**Technical (unit):**
- DTO/domain round-trip: `timedEffort` serializes/deserializes; null-tolerant for
  legacy drafts.
- Backend `LoggedSet` accepts and persists the field; old constructors still
  compile.
- Gate logic: final timed set cannot complete without a selection; non-final sets
  never require it.

**Functional:**
- Instrumented/UI test: on the final set of a timed exercise the three icons
  appear; completion is blocked until one is tapped; the choice persists across a
  process-death reload of the draft (Room JSON round-trip).
- The signal appears in the completed-set history row.
- End-to-end: a completed session's payload (`LogSessionRequest`) carries
  `timedEffort` on the final timed set to the backend, and the backend still
  **skips** timed exercises in progression (assert e1RM/next-prescription
  unchanged for the timed exercise — proves "capture only").

### Definition of Done (Phase 4)

- [ ] New `timedEffort` field on backend + Android domain + DTO, null-tolerant.
- [ ] Three-icon selector on final timed set, capability semantics (up=more).
- [ ] Required gate enforced on final set; coordinated with Phase 3 auto-advance.
- [ ] Value persists across process death and reaches the backend on completion.
- [ ] Shown in history. Progression provably unchanged (still skipped).

### Agent Verification (paste command + output before marking done)

```
./gradlew :feature-workouts:testDebugUnitTest --tests '*Timed*' --tests '*WorkoutSession*'
./gradlew :core-data:testDebugUnitTest --tests '*WorkoutProgramDto*'
./gradlew :backend:test --tests '*LoggedSet*' --tests '*SessionLoop*' --tests '*Progression*'
```
- [ ] Above pass.
- [ ] Manual in-app: complete a timed exercise; confirm you must pick an icon on the
      final set, the choice shows in history, and progression numbers for that timed
      exercise don't move. Note observed: _(fill in)_

---

## Phase 5 (deferred) — Drive duration progression from more/same/less

**Status:** Deferred — do NOT build in this fix pack (D-TF-DRIVE = capture now).

Once Phase 4's signal has been validated on real sessions, a later effort will:
un-skip timed exercises in `SessionLoop.java:91`, add a duration-progression rule
(more → lengthen next duration, same → hold, less → shorten), and surface the
rationale in the coach announcement. Tracked here so the captured data has a
known destination. Requires its own spec + decisions (step size, caps, how
`timedEffort` maps to a duration delta, interaction with the Kalman/e1RM model
which is load-based and does not apply to holds).

---

## Cross-cutting: testing philosophy & Definition of Done

**Every phase must prove functional behavior, not just unit correctness (owner
requirement).** Concretely:

1. **Red-before-green for bugs.** Phases 1 and 3 fix live bugs — each must add a
   test that FAILS on the current code and PASSES after the fix. Paste both
   results.
2. **Functional over unit for data-integrity.** Nutrition phases assert the
   **day's** totals (the thing the user sees), end-to-end through the offline
   mirror — not merely an entry-level recompute (D-TEST-NUT).
3. **State-machine coverage for the timer.** Phase 3 must exercise the
   timed→timed transition as a sequence (log → rest → get-ready → hold) with the
   Skip control, plus the last-set-of-session regression.
4. **Offline parity.** Nutrition mutations (qty edit, removal) must be tested with
   the network unavailable — the offline-first refactor is the suspected cause of
   #1, so offline behavior is a first-class test target, not an afterthought.
5. **Agent self-verification before "done".** No phase dimension is marked `[x]`
   from assertion. The agent runs the phase's Agent Verification block, pastes the
   command + output, and (for UI-facing phases) runs the in-app `run`/`verify`
   skill and records the observed before/after. If it can't be observed, it isn't
   done.

**Branch/push discipline (D-DELIV):** work on `bug-fixes`. A phase is "Pushed"
when its commit(s) are on the pushed branch and the roll-up table is updated. Keep
commits phase-scoped so any phase can be cherry-picked/reverted independently.
End commit messages with the required `Co-Authored-By` trailer.

**Global Definition of Done (all four items):**
- [ ] All four phases: Implemented / Tested (tech) / Tested (func) / Pushed = `[x]`.
- [ ] Roll-up table at top updated.
- [ ] Full relevant test suites green (`./gradlew` module tests above; no new
      flakes introduced — see `flaky-syncenginepulltest-lww` memory for a known one).
- [ ] Manual in-app verification recorded for each user-facing phase (1, 2, 3, 4).
- [ ] No regression to the already-shipped whole-meal-portion fix (`f6da37ee`) or
      the rest-timer dual-state fix (`31c80438`).
