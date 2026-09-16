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

_(entries appended during implementation)_

## Phase 3 — Timed timer/rest fix

_(entries appended during implementation)_

## Phase 4 — Timed more/same/less capture

_(entries appended during implementation)_
