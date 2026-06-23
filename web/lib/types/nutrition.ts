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

export type Meal = "BREAKFAST" | "LUNCH" | "DINNER" | "SNACK";

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
};

export const MEAL_ICONS: Record<Meal, string> = {
  BREAKFAST: "coffee",
  LUNCH: "salad",
  DINNER: "soup",
  SNACK: "apple",
};

export const MEALS: Meal[] = ["BREAKFAST", "LUNCH", "DINNER", "SNACK"];

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
