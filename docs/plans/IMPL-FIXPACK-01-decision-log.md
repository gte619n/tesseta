# IMPL-FIXPACK-01 — Implementation Decision Log

Companion to `IMPL-FIXPACK-01-nutrition-workout-fixes.md`. Every non-trivial
decision made during autonomous implementation is recorded here (with reasoning)
for post-hoc review. Newest entries appended per phase.

Format: `DL-<phase>-<n>` · what was decided · why · reversible?

---

## Phase 1 — Ingredient qty → day totals

**Confirmed root cause.** `NutritionRepository.updateIngredient` (the per-ingredient
path used by `saveCompositeMeal`) is the *only* nutrition mutation still
network-first after the offline-first refactor (#258): it calls
`api.updateIngredient(...)` then `fillDayAndSignal` → `fillDay` → `api.getDay` →
`refreshInto`. `refreshInto` deliberately **skips rows where `existing.dirty ==
true`** (so it never clobbers a pending optimistic edit). A composite entry that
is still dirty/PENDING (just logged, or edited since the last sync) therefore
never receives the server's re-scaled macros back into the mirror, and the mirror
-assembled day total (`assembleDay` sums `entry.macros`) stays at the old value —
the reported "day totals don't change." Whole-meal portion edits don't hit this
because `patchEntry` was already moved to the offline-first local re-sum rail
(f6da37ee); per-ingredient edits were left on the network rail.

- **DL-1-1** — Move per-ingredient qty edits onto the same offline-first rail as
  `patchEntry`, and *unify* the whole composite save (title + portion + all
  ingredient qty changes) into a **single local mirror write**. Reasoning: (a)
  fixes the dirty-row-skip silent failure deterministically — the client computes
  the new totals and writes them to the mirror itself; (b) makes ingredient edits
  work offline like every other mutation; (c) the write rides the outbox UPDATE
  (`updateLocal`), so backend + web converge via the exact rail `patchEntry`
  already uses in production. Reversible (isolated to repo + VM).
- **DL-1-2** — Per-ingredient macros recomputed client-side as
  `macrosPer100g.forPortion(servingGrams, qty)`, matching both the on-screen live
  preview (`IngredientCard`) and the backend `CompositeIngredient.withPortion`
  (`factor = grams*qty/100`). So the saved total equals exactly what the user saw
  in the sheet (WYSIWYG). Ingredients lacking a `macrosPer100g` baseline keep
  their frozen `macros` (can't re-scale) — same fallback the preview uses.
- **DL-1-3** — Entry total recomputed with the existing private
  `compositeTotal(ingredients, portion)` (sum of ingredient `macros` × portion),
  which already mirrors `NutritionService.compositeTotal`. No new formula.
- **DL-1-4** — Removed the now-unused network `NutritionRepository.updateIngredient`
  (its only caller was `saveCompositeMeal`). Kept `NutritionApi.updateIngredient`
  + `UpdateIngredientRequest` (backend REST contract; web still uses the REST
  endpoint). Reversible.
- **DL-1-5** — Web path (D-QTY-SCOPE): web PATCHes ingredients via the REST
  endpoint (`NutritionService.updateIngredient`) which re-scales + `recomputeDay`
  server-side and returns the fresh entry synchronously — not affected by the
  Android mirror dirty-skip. Left unchanged; no web code touched. Noted for the
  functional-verification step.
- **DL-1-6** (red-before-green note) — The literal old code path (`repository.
  updateIngredient` network call) was removed by DL-1-4, so a byte-for-byte red
  run isn't possible post-fix. The new test
  `editing an ingredient quantity moves the day total even for a still-dirty composite`
  encodes the exact failure mode: on the old path `fillDay`'s `refreshInto` skips
  the dirty row, so the day total would assert 500.0 (stale) instead of 350.0.
  Functionally red-before-green by construction. All 4 new day-total tests pass
  (`:core-data:testDebugUnitTest`).

## Phase 2 — Remove ingredient

- **DL-2-1** (deviation from spec Step 1) — Removal is implemented as an
  **offline-first local mirror write** (`NutritionRepository.removeIngredient` /
  `restoreIngredient`), NOT a new REST `DELETE .../ingredients/{index}` endpoint.
  Reasoning: after #258 every nutrition mutation is offline-first and rides the
  outbox; Phase 1 just fixed a bug caused precisely by the *one* mutation left on
  the network rail. Adding a network-first delete would reintroduce that class of
  bug (offline breakage + dirty-row skips) and be inconsistent. The removal writes
  the re-summed entry via `updateLocal`, which rides the outbox UPDATE to the
  server + other devices — the exact rail `patchEntry`/`updateComposite` use. No
  backend change needed; the server LWW-stores the pushed doc (fewer ingredients +
  recomputed macros), same shape it already accepts. Reversible.
- **DL-2-2** (undo) — `removeIngredient` returns the removed `EntryIngredient`;
  the VM stashes it (`lastRemovedIngredient`) so a snackbar **Undo** re-inserts it
  at its original index via `restoreIngredient` and re-sums. Snackbar is hosted
  inside the IngredientsSheet (like DrinkCard's self-hosted snackbar).
- **DL-2-3** (last-ingredient guard) — Removing the final ingredient is refused
  (repo throws; the sheet also disables the trash icon when one ingredient
  remains). A composite with zero ingredients is invalid (its total would be
  meaningless). Chose refuse over auto-convert-to-single to keep it predictable.
- **DL-2-4** (opt-in AI re-title, D-RM-TITLE) — Delivered by **reusing the shipped
  Adjust-with-AI flow** (`onSubmitAdjust`) rather than a new Gemini "rename"
  endpoint. After a removal the sheet shows a one-tap "Re-title with AI" that
  submits a constrained instruction naming the removed item(s) and asking only to
  rename to match remaining ingredients (not add anything), `saveAsMeal=false`
  (D-RM-SAVED). The adjust flow's built-in preview/review step lets the user
  discard if the re-analysis re-adds the item — mitigating the re-add risk while
  avoiding new AI wiring. Reversible.
- **DL-2-5** (opt-in image regen, D-RM-PHOTO) — Delivered by reusing the existing
  `regenerateEntryImage` (backend `POST .../image/regenerate`), surfaced as a
  one-tap "Regenerate photo" after a removal. It regenerates the finished-meal
  image from the entry's current name — reflecting the removal once it syncs.
- **DL-2-6** (sheet state) — The sheet is driven by the reactive `entry`; the
  per-ingredient `quantities` state is keyed on `entry.entryId` + ingredient count
  so it re-initialises when an ingredient is removed/restored (indices stay
  aligned with the persisted list). Tradeoff: an un-saved in-progress qty edit is
  discarded when a *different* ingredient is removed in the same session — minor,
  and removal is an infrequent corrective action.
- **DL-2-7** (save-as-meal untouched, D-RM-SAVED) — Removal only writes the
  logged day entry; nothing calls the save-as-meal catalog path, and the re-title
  reuse passes `saveAsMeal=false`. No catalog copy is modified.

## Phase 3 — Timed timer/rest fix

**Confirmed root cause.** Timed sets logged with `startRestOrComplete(startRest =
false)` (`WorkoutSessionViewModel.logTimedSet`), so a completed timed exercise
started **no rest**. The screen's auto-advance instead handed off directly to the
next hold's **get-ready pre-roll** (`autoStartStep = nextIndex` for timed→timed),
which auto-started via `HoldTimer`'s `LaunchedEffect(autoStart)`. That pre-roll's
only controls (Start now / Pause / Reset) live *inside* the hold card; the
screen-level overlay/bar render a **Skip only for `Kind.REST`, not `GET_READY`**.
During the page auto-advance the card is mid-transition, so the countdown ran with
no reachable Skip/stop — "the timer continued to run and I had no way to reset or
stop it," and because it was a short get-ready (next exercise's `restSeconds`, or
the 10s fallback) rather than a real rest — "it skipped the rest entirely."

- **DL-3-1** (rest between timed exercises, D-TMR-REST) — `logTimedSet` now starts
  the prescribed rest when the timed set *completes the exercise* (last set) and
  the session isn't over — symmetric with rep sets (`startRest = exerciseDone`).
  Between sets of the *same* hold the get-ready pre-roll still paces the next hold
  (unchanged), so a rest is folded in only at the exercise boundary. A `Kind.REST`
  renders the screen-level Skip on both the bar and the overlay, so it is always
  controllable and never orphaned (fixes D-TMR-CTRL too).
- **DL-3-2** (controllable get-ready after rest, D-TMR-TRANS) — Removed the direct
  timed→timed `autoStartStep` get-ready hand-off (the uncontrolled runaway). A new
  screen effect starts a **short** get-ready (`GET_READY_SECONDS` = 10s, not the
  full `restSeconds`, since the rest already gave recovery) once the between-exercise
  rest ends (expired or skipped) on a timed page whose first set hasn't started.
  `HoldTimer` then auto-starts the hold when that get-ready expires, and its
  Pause/Reset/Start-now controls are present — auto-advance kept, fully controllable.
- **DL-3-3** (no competing timers / race) — Because the get-ready is armed only
  *after* the rest clears, a rest and a get-ready are never live at once, so
  nothing clobbers or orphans the other. `clearRest` on genuine session completion
  is unchanged (still fires only when `draft.isComplete`). Rep→timed transitions
  benefit too: the rep rest ends → the timed page gets a get-ready.
- **DL-3-4** (bonus generality) — The post-rest get-ready effect keys on "a REST
  just ended on a timed page with 0 logged sets," so it also smooths rep→timed and
  manual-swipe-into-timed, without affecting rep→rep (rep pages aren't timed).
- **DL-3-5** (test coverage boundary) — The behavioural core (a completed timed
  exercise starts the prescribed rest; a non-final timed set does not; the session
  end still auto-completes with no trailing rest) is covered by
  `WorkoutSessionViewModelTest` (new + existing tests pass, no regression). The
  post-rest → get-ready hand-off lives in a Compose `LaunchedEffect` in
  `WorkoutSessionScreen`; there's no full-screen Compose UI-test harness in this
  module, so it's validated by construction + the manual in-app verification step
  in the spec (two back-to-back timed exercises → controllable rest w/ Skip →
  get-ready → hold). The notification-content builder test still passes (no
  orphaned notification).

## Phase 4 — Timed more/same/less capture

_(entries appended during implementation)_
