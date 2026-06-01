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
  servingLabel: string;
  servingGrams: number;
  quantity: number;
  macros: Macros;
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
