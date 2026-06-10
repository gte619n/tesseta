# IMPL-16: Implementation Decisions Log

Questions that came up during implementation and the decisions made
autonomously, for post-hoc review. Companion to
[IMPL-16-med-reminders-and-nutrition-ux.md](IMPL-16-med-reminders-and-nutrition-ux.md).

Format: each entry states the question, the decision, the rationale, and
where in the code it landed.

---

## Part D — calories derived from macros

### D-1: Calories-only entries stay loggable
**Q:** If calories are always derived from macros, what happens to a quick
add where the user knows only the calories (e.g. a 150 kcal beer) and enters
no macros?
**Decision:** Derivation only kicks in when at least one of protein/carbs/fat
is non-null. With all three absent, the supplied calories are kept verbatim.
Explicit zeros DO derive (0 g macros ⇒ 0 kcal) — entering `0/0/0 + 500 kcal`
is treated as physically impossible and corrected to 0.
**Where:** `Macros.withDerivedCalories()` (`core/nutrition/Macros.java`).

### D-2: Derivation lives in the service layer, not DTO validation
**Q:** Reject inconsistent input (400) or silently correct it?
**Decision:** Silently correct at the service chokepoints
(`NutritionService.logDay/addEntry/updateEntry/addCompositeMeal/
finalizeCompositeMeal/finalizeSingleFood/updateIngredient/compositeTotal`,
`FoodCatalogService.create`). The server is the source of truth; clients
additionally make the calories field read-only so the user is never surprised
by the correction.

### D-3: Carbs include fiber (no fiber subtraction)
**Q:** US labels count fiber inside total carbs, and fiber contributes
~2 kcal/g rather than 4. Subtract it?
**Decision:** Plain 4/4/9 with carbs as given (fiber included). Simple,
matches label convention, and consistent everywhere. Alcohol has no field in
the data model, so 7 kcal/g is out of scope.

### D-4: AI/label extraction is corrected at the source
**Q:** Apply derivation only on persist, or also on AI proposals the user
previews?
**Decision:** Also derive inside `MealPhotoExtractor`,
`MealDescriptionExtractor` and `NutritionLabelExtractor`, so previews,
proposals and saved meals already show the corrected kcal — what you preview
is exactly what gets logged. Consequence: a scanned label's stored kcal can
differ slightly from the printed panel (labels legally round).

### D-5: OFF/USDA catalog data is left untouched
**Q:** Rewrite Open Food Facts / USDA seeded `macrosPer100g` too?
**Decision:** No — upstream source data is preserved as imported (ODbL/CC0
provenance), but any *entry logged from it* gets derived calories via
`addEntry`. Foods created through `FoodCatalogService.create` (manual + all
Gemini sources) do derive.

### D-6: Existing stored data is healed lazily, not migrated
**Q:** Backfill existing Firestore entries/day rollups?
**Decision:** No migration. Any edit/re-portion of a legacy entry re-derives
(composite normalization in `NutritionService` heals per-ingredient values),
and day rollups re-derive on the next entry mutation of that day. Historical
untouched days keep their original kcal.

### D-7: Pre-existing test fixtures updated, not worked around
Four backend tests asserted calorie values inconsistent with their own macro
fixtures (e.g. label kcal 200 vs derived 192). The expectations were updated
to the derived values — this is precisely the intended behavior change.
