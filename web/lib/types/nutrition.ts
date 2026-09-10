// Types for the Nutrition module (IMPL-13).
// Mirror the backend REST contract — keep in sync when DTOs change.

export type Macros = {
  caloriesKcal: number | null;
  proteinGrams: number | null;
  carbsGrams: number | null;
  fatGrams: number | null;
  fiberGrams: number | null;
  sugarGrams: number | null;
};

export type Meal = "BREAKFAST" | "LUNCH" | "DINNER" | "SNACK" | "DRINKS";

export type EntrySource = "MANUAL" | "CATALOG" | "BARCODE" | "LABEL" | "PHOTO";

export type AnalysisStatus = "NONE" | "ANALYZING" | "READY" | "FAILED";

export type Entry = {
  entryId: string;
  meal: Meal;
  foodId: string | null;
  foodName: string;
  servingLabel: string;
  servingGrams: number;
  quantity: number;
  macros: Macros;
  source: EntrySource;
  // Joined in from the entry's catalog food (null/NONE for manual entries).
  // For a composite (photo-logged) meal this is the finished-meal image.
  imageUrl: string | null;
  imageStatus: ImageStatus;
  // Background AI-analysis lifecycle: ANALYZING while a captured photo or an
  // async-described meal is still resolving server-side, then READY/FAILED.
  analysisStatus?: AnalysisStatus;
  // Present for a composite meal: its components, each with a raw-ingredient
  // image. Null/absent for a plain single-food entry.
  ingredients?: EntryIngredient[] | null;
  // The day the entry was logged on (ISO yyyy-MM-dd). Populated by reads that
  // span days (recent-meals), where it identifies the source for a re-log.
  date?: string;
  // ISO timestamp of when the entry was first logged (server-stamped). Null for
  // a not-yet-persisted placeholder (e.g. an in-flight photo capture).
  createdAt: string | null;
};

export type EntryIngredient = {
  name: string;
  foodId: string | null;
  servingLabel: string | null;
  servingGrams: number | null;
  quantity: number | null;
  macros: Macros;
  macrosPer100g: Macros | null;
  imageUrl: string | null;
  imageStatus: ImageStatus;
};

/**
 * An entry is supposed to have a generated picture when it's a composite meal
 * (its own finished-meal image) or catalog-backed (its studio image, joined
 * server-side). A pure "quick add" (no foodId, no ingredients) legitimately has
 * none — so it never offers a retry.
 */
export function isImageEligible(
  e: Pick<Entry, "foodId" | "ingredients">,
): boolean {
  return e.foodId != null || (e.ingredients?.length ?? 0) > 0;
}

/**
 * True when a picture is expected but absent and not currently being produced —
 * NONE (never generated) or FAILED, and not mid-analysis or PENDING. Drives the
 * always-visible retry control and keeps the page polling so the server's
 * self-heal converges the image without user action.
 */
export function isImageMissing(
  e: Pick<Entry, "foodId" | "ingredients" | "imageStatus" | "analysisStatus">,
): boolean {
  return (
    isImageEligible(e) &&
    e.analysisStatus !== "ANALYZING" &&
    e.imageStatus !== "READY" &&
    e.imageStatus !== "PENDING"
  );
}

export type MealGroup = {
  meal: Meal;
  subtotal: Macros;
  entries: Entry[];
};

export type NutritionDay = {
  date: string;
  totals: Macros;
  target: Macros | null;
  meals: MealGroup[];
};

export type DailyRollup = {
  date: string;
  proteinGrams: number;
  carbsGrams: number;
  fatGrams: number;
  fiberGrams: number;
  sugarGrams: number;
  caloriesKcal: number;
};

// Food catalog types
export type FoodSource =
  | "USDA"
  | "OPEN_FOOD_FACTS"
  | "USER"
  | "GEMINI_PHOTO"
  | "GEMINI_LABEL";

export type FoodStatus = "UNVERIFIED" | "VERIFIED";

export type ImageStatus = "NONE" | "PENDING" | "READY" | "FAILED";

export type ServingSize = {
  label: string;
  grams: number;
};

export type Food = {
  foodId: string;
  name: string;
  brand: string | null;
  barcode: string | null;
  category: string | null;
  macrosPer100g: Macros;
  servingSizes: ServingSize[];
  defaultServingIndex: number;
  source: FoodSource;
  status: FoodStatus;
  confirmationCount: number;
  imageUrl: string | null;
  imageStatus: ImageStatus;
};

// ── Drinks (IMPL-DRINK-01) ───────────────────────────────────────────
// A drink is a CatalogFood with category="drink" and a non-null `alcohol`
// block. The backend derives the alcohol math (grams/std drinks) and folds
// alcohol calories into the food's calories. See DrinkController.

// Deterministic alcohol readouts (backend-computed from ABV% + serving volume;
// §4.1 of the spec). Present on any drink food + on an analyze proposal.
export type AlcoholInfo = {
  abvPercent: number | null;
  servingVolumeMl: number | null;
  alcoholGrams: number | null;
  standardDrinks: number | null;
};

// A drink in my catalog — the FoodResponse shape narrowed to drinks. `alcohol`
// is non-null and `category === "drink"`.
export type Drink = Food & {
  sourceRef?: string | null;
  createdBy?: string | null;
  alcohol: AlcoholInfo | null;
  // Per-serving macros the backend echoes for drinks (rounded 1 dp), so the edit
  // form shows exactly what was entered without re-deriving from `macrosPer100g`
  // (which drifts on float round-trips). `caloriesKcal` is the full serving total
  // INCLUDING alcohol; the mixer components (protein/carbs/fat/fiber/sugar) are the
  // per-serving mixer contribution. Null for non-drinks. (IMPL-DRINK-01 IL-13.)
  servingMacros: Macros | null;
};

// Result of POST /api/me/drinks/analyze — a non-persisted AI proposal. `macros`
// is the per-serving mixer contribution (carbs/sugar/…), EXCLUDING alcohol
// calories; the derived `alcohol` readouts come alongside. On analyzer failure
// the backend returns 422 (surfaced as a manual-entry fallback in the UI).
export type DrinkProposal = {
  name: string;
  abvPercent: number | null;
  servingVolumeMl: number | null;
  servingMacros: Macros;
  alcohol: AlcoholInfo | null;
};

// Body for POST/PUT /api/me/drinks. `macros` is the per-serving mixer
// contribution EXCLUDING alcohol calories; the backend computes total calories.
export type SaveDrinkBody = {
  id?: string;
  name: string;
  abvPercent: number;
  servingVolumeMl: number;
  servingLabel?: string | null;
  macros?: Macros | null;
};

// A saved-meal hit in the add-food search (GET /api/me/nutrition/meals/search).
// Logged by `mealId` via the describe-meal path, which reuses the meal's
// ingredient breakdown + plated photo. `macros`/`totalGrams` are one serving.
export type MealSearchResult = {
  mealId: string;
  name: string;
  macros: Macros;
  totalGrams: number | null;
  imageUrl: string | null;
  imageStatus: ImageStatus;
  mine: boolean;
};

// Request bodies
export type AddEntryBody = {
  meal: Meal;
  foodId: string | null;
  foodName: string;
  servingLabel: string;
  servingGrams: number;
  quantity: number;
  macros: Macros;
  source: EntrySource;
};

export type UpdateEntryBody = Partial<{
  meal: Meal;
  foodName: string;
  servingLabel: string;
  servingGrams: number;
  quantity: number;
  macros: Macros;
}>;

export type UpdateIngredientBody = Partial<{
  servingGrams: number;
  servingLabel: string;
  quantity: number;
}>;

export type CreateFoodBody = {
  name: string;
  brand?: string | null;
  barcode?: string | null;
  category?: string | null;
  macrosPer100g: Macros;
  servingSizes: ServingSize[];
  defaultServingIndex: number;
};

// ── Describe a meal ──────────────────────────────────────────────────
// One component of a described meal (with its frozen per-100g baseline).
export type DescribedIngredient = {
  name: string;
  servingGrams: number | null;
  servingLabel: string | null;
  quantity: number | null;
  macros: Macros;
  macrosPer100g: Macros;
};

// Result of POST /api/nutrition/describe: a resolved meal — either a
// previously-saved match (`matched`) or a freshly created one — with its
// macros, ingredient breakdown and studio-photo status.
export type DescribedMeal = {
  mealId: string;
  matched: boolean;
  name: string;
  totalGrams: number | null;
  macros: Macros;
  imageUrl: string | null;
  imageStatus: ImageStatus;
  ingredients: DescribedIngredient[];
};

// Body for POST /api/me/nutrition/{date}/describe-meal: log a resolved meal by
// `mealId`, or one-shot by raw `description`.
export type LogDescribedMealBody = {
  mealId?: string;
  description?: string;
  meal: Meal;
};

// ── Adjust with AI ───────────────────────────────────────────────────
// One component of an AI meal correction — a proposed item on preview, or an
// accepted item echoed back on apply. Mirrors the backend AdjustItemDto.
export type AdjustItem = {
  name: string;
  servingLabel: string | null;
  servingGrams: number | null;
  macrosPer100g: Macros | null;
  macros: Macros | null;
};

// Result of POST …/adjust/preview: the revised meal as a non-persisted proposal
// plus the before/after day-total macros so the client can render the diff.
export type AdjustPreviewResponse = {
  mealName: string;
  packagedProduct: boolean;
  items: AdjustItem[];
  newTotals: Macros;
  oldTotals: Macros;
};

// Body for POST …/adjust/apply: the accepted proposal echoed back, plus whether
// to also save the corrected meal to the shared catalog for reuse.
export type AdjustApplyBody = {
  mealName: string;
  packagedProduct: boolean;
  items: AdjustItem[];
  saveAsMeal: boolean;
};

// Body for POST /api/me/nutrition/{date}/relog: one-tap copy of a past entry.
export type RelogBody = {
  sourceDate: string;
  sourceEntryId: string;
  meal: Meal;
};

// Display helpers
export const MEAL_LABELS: Record<Meal, string> = {
  BREAKFAST: "Breakfast",
  LUNCH: "Lunch",
  DINNER: "Dinner",
  SNACK: "Snack",
  DRINKS: "Drinks",
};

export const MEAL_ICONS: Record<Meal, string> = {
  BREAKFAST: "coffee",
  LUNCH: "salad",
  DINNER: "soup",
  SNACK: "apple",
  DRINKS: "glass-cocktail",
};

// The always-present meal sections in the day view. DRINKS is intentionally
// excluded — its section only appears when the day has drink entries (D8), so
// it's appended separately from the server response rather than pre-seeded here.
export const MEALS: Meal[] = ["BREAKFAST", "LUNCH", "DINNER", "SNACK"];

// ── Drink alcohol math (IMPL-DRINK-01 §4.1) ──────────────────────────
// The backend is the single source of truth for these; this mirror lets the
// web review/edit form show the derived readouts live as the user types. Keep
// the constants in sync with DrinkMath on the backend.
const ETHANOL_DENSITY_G_PER_ML = 0.789;
const STANDARD_DRINK_GRAMS = 14.0;
const ALCOHOL_KCAL_PER_GRAM = 7.0;

/** Pure ethanol mass (g) for a serving; null when inputs are missing/invalid. */
export function alcoholGrams(
  abvPercent: number | null,
  servingVolumeMl: number | null,
): number | null {
  if (!abvPercent || !servingVolumeMl) return null;
  return servingVolumeMl * (abvPercent / 100) * ETHANOL_DENSITY_G_PER_ML;
}

/** US standard drinks (14 g each) for a serving. */
export function standardDrinks(
  abvPercent: number | null,
  servingVolumeMl: number | null,
): number | null {
  const g = alcoholGrams(abvPercent, servingVolumeMl);
  return g === null ? null : g / STANDARD_DRINK_GRAMS;
}

/**
 * Total serving calories for a drink: macro-derived (Atwater 4/4/9 on the mixer
 * contribution) plus alcohol calories (7 kcal/g). Mirrors the backend's drink
 * calorie model (decision IL-3), which bypasses plain macro re-derivation so
 * alcohol calories aren't erased.
 */
export function drinkCaloriesKcal(
  proteinGrams: number | null,
  carbsGrams: number | null,
  fatGrams: number | null,
  abvPercent: number | null,
  servingVolumeMl: number | null,
): number | null {
  const g = alcoholGrams(abvPercent, servingVolumeMl);
  if (
    proteinGrams === null &&
    carbsGrams === null &&
    fatGrams === null &&
    g === null
  ) {
    return null;
  }
  const macroKcal =
    (proteinGrams ?? 0) * 4 + (carbsGrams ?? 0) * 4 + (fatGrams ?? 0) * 9;
  return macroKcal + (g ?? 0) * ALCOHOL_KCAL_PER_GRAM;
}

export const QUANTITY_STEPS = [0.5, 1, 1.5, 2] as const;
export type QuantityStep = (typeof QUANTITY_STEPS)[number];

/**
 * Infer the meal from the local hour of day (same windows as Android/backend):
 * breakfast 04–10, lunch 11–15, dinner 16–21, snack otherwise.
 */
export function mealForHour(hour: number): Meal {
  if (hour >= 4 && hour <= 10) return "BREAKFAST";
  if (hour >= 11 && hour <= 15) return "LUNCH";
  if (hour >= 16 && hour <= 21) return "DINNER";
  return "SNACK";
}

/**
 * Calories derived from macros under Atwater 4/4/9 — the invariant the backend
 * enforces on every write, so forms can show the value live as the user types.
 * Null when no macro is present at all (calories-only entries stay enterable).
 */
export function derivedCaloriesKcal(
  proteinGrams: number | null,
  carbsGrams: number | null,
  fatGrams: number | null,
): number | null {
  if (proteinGrams === null && carbsGrams === null && fatGrams === null) return null;
  return (proteinGrams ?? 0) * 4 + (carbsGrams ?? 0) * 4 + (fatGrams ?? 0) * 9;
}
