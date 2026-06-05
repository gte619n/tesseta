# ADR-0011: "Describe a meal" via a shared, reusable saved-meal catalog

- Status: Accepted
- Date: 2026-06-05

## Context

Nutrition logging today is **photo-first**: `MealPhotoExtractor`
(`gemini-3.5-flash` tool calling) itemizes a meal photo, and
`GeminiFoodImageGenerator` (`gemini-3.1-flash-image-preview`) renders the studio
photo. There was no way to log a meal from a **text description**, and no notion
of a *reusable meal* — composite meals lived only as per-day `FoodEntry` rows
(strictly `(userId, date)`-keyed, no cross-day or name query), so the same meal
re-described tomorrow would be re-analyzed and re-photographed from scratch.

We want: describe a meal in free text → find a previously-logged meal that
matches → if none, create one with correct macros and matching photography, and
save it so the next description reuses it.

## Decision

1. **Text path mirrors the photo path.** A new `MealDescriptionAnalyzer` port +
   `MealDescriptionExtractor` integration are the text-input sibling of
   `MealPhotoAnalyzer`/`MealPhotoExtractor`, reusing the same
   `gemini-3.5-flash` model and the shared `MealAnalysis`/`MealItem` shapes. No
   new model or provider (per root `CLAUDE.md` / ADR-0005).

2. **A shared `mealCatalog` collection.** Reusable meals are persisted as
   `SavedMeal` (top-level `mealCatalog/{mealId}`, shared across users like
   `foodCatalog`), tagged with `createdBy`. It carries the **full ingredient
   breakdown** (so a reuse reconstructs a composite entry with per-ingredient
   rows), per-serving macros, and its own generated studio photo
   (`SavedMealImageService`, mirroring `FoodImageService`). `FoodSource` gains
   `GEMINI_DESCRIPTION`.

3. **Gemini-assisted matching, user-first.** A description is itemized, the
   catalog is name-prefix searched (the requesting user's own meals ranked
   first, then global), and a second cheap flash tool call (`choose_meal_match`)
   confirms a genuine same-dish match — biased toward creating a new meal over a
   loose match. No embedding store is introduced.

4. **Resolve persists; logging is by id.** `POST /api/nutrition/describe`
   resolves a description to a concrete `SavedMeal` (existing match or
   freshly-created-and-saved) so it is findable next time;
   `POST /api/me/nutrition/{date}/describe-meal` logs it onto a day (by `mealId`,
   or one-shot by `description`).

## Consequences

- Described-meal **ingredients carry no catalog `foodId`** (no per-ingredient
  raw images are generated, unlike the photo flow) — the meal gets a single
  plated photo. This keeps cost bounded; per-ingredient images can be added
  later if wanted.
- A **brand-new** described meal generates two studio photos on first log (the
  `SavedMeal`'s catalog thumbnail and the day entry's finished-meal image, the
  latter via the existing `FoodEntryImageService`). A **reused** meal generates
  none — its `READY` photo is attached to the entry. This avoided adding a
  `mealId` field to `FoodEntry` and coupling the day-view image join.
- `mealCatalog` name-prefix search needs the same single-field `nameLower`
  ordering the food catalog uses; no composite index.
- Previewing a description without logging can leave an orphan `SavedMeal` in the
  shared catalog (it is still a valid, reusable, photographed meal).
