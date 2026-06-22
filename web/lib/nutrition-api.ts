import { apiFetch, apiJson, BackendError, send } from "./api";
import type {
  NutritionDay,
  Macros,
  Entry,
  Food,
  DailyRollup,
  AddEntryBody,
  UpdateEntryBody,
  UpdateIngredientBody,
  CreateFoodBody,
  DescribedMeal,
  LogDescribedMealBody,
  RelogBody,
  Meal,
} from "./types/nutrition";

// Server-only HTTP helpers for the Nutrition module (IMPL-13).
// Do NOT import from "use client" components — apiFetch reads server env
// + the Auth.js session. Define mutations as inline server actions in
// server pages and pass them down as props. The shared `send` JSON mutation
// helper lives in lib/api.ts.

// ── Reads ────────────────────────────────────────────────────────────

/** Fetch a single day's entries + totals + target. */
export function getDay(date: string): Promise<NutritionDay> {
  return apiJson<NutritionDay>(`/api/me/nutrition/${date}`);
}

/**
 * Fetch the active macro target.
 * Returns null when no target has been set (204 No Content from backend).
 */
export async function getTarget(): Promise<Macros | null> {
  const res = await apiFetch("/api/me/nutrition/target");
  if (res.status === 204) return null;
  if (!res.ok) {
    throw new BackendError(`GET /api/me/nutrition/target returned ${res.status}`, res.status);
  }
  return res.json() as Promise<Macros>;
}

/** Fetch daily rollups over a date range (for History view). */
export function getHistory(from: string, to: string): Promise<DailyRollup[]> {
  return apiJson<DailyRollup[]>(`/api/me/nutrition?from=${from}&to=${to}`);
}

// ── Food catalog reads ───────────────────────────────────────────────

/** Name-prefix search over the shared food catalog. */
export function searchFoods(q: string): Promise<Food[]> {
  return apiJson<Food[]>(`/api/foods/search?q=${encodeURIComponent(q)}`);
}

/** Fetch a single catalog food by id. */
export function getFood(foodId: string): Promise<Food> {
  return apiJson<Food>(`/api/foods/${foodId}`);
}

// ── Mutations ────────────────────────────────────────────────────────

/** Set / replace the active macro target. */
export function setTarget(macros: Macros): Promise<Macros> {
  return send<Macros>("/api/me/nutrition/target", "PUT", macros);
}

/** Add a food entry to a day+meal. */
export function addEntry(date: string, body: AddEntryBody): Promise<Entry> {
  return send<Entry>(`/api/me/nutrition/${date}/entries`, "POST", body);
}

/** Edit an existing entry (partial update). */
export function updateEntry(
  date: string,
  entryId: string,
  body: UpdateEntryBody,
): Promise<Entry> {
  return send<Entry>(
    `/api/me/nutrition/${date}/entries/${entryId}`,
    "PATCH",
    body,
  );
}

/** Remove an entry from the day. */
export function deleteEntry(date: string, entryId: string): Promise<void> {
  return send<void>(`/api/me/nutrition/${date}/entries/${entryId}`, "DELETE");
}

/**
 * Retry image generation for an entry whose image FAILED (or is stuck). The
 * backend flips it to PENDING and regenerates async; the day's
 * PendingImageRefresher then polls until it's READY.
 */
export function regenerateEntryImage(date: string, entryId: string): Promise<Entry> {
  return send<Entry>(
    `/api/me/nutrition/${date}/entries/${entryId}/image/regenerate`,
    "POST",
  );
}

/** Re-portion one ingredient of a composite meal (by index). */
export function updateIngredient(
  date: string,
  entryId: string,
  index: number,
  body: UpdateIngredientBody,
): Promise<Entry> {
  return send<Entry>(
    `/api/me/nutrition/${date}/entries/${entryId}/ingredients/${index}`,
    "PATCH",
    body,
  );
}

/** Create a new catalog food (manual / AI-confirmed). */
export function createFood(body: CreateFoodBody): Promise<Food> {
  return send<Food>("/api/foods", "POST", body);
}

// ── Describe a meal ──────────────────────────────────────────────────

/**
 * Resolve a free-text meal description to a saved meal — a previously-saved
 * match (user's own first) or a freshly created+saved one with AI macros and a
 * generating studio photo. Nothing is logged onto a day yet.
 */
export function describeMeal(description: string): Promise<DescribedMeal> {
  return send<DescribedMeal>("/api/nutrition/describe", "POST", { description });
}

/** Log a described meal onto a day — by resolved `mealId`, or one-shot `description`. */
export function logDescribedMeal(
  date: string,
  body: LogDescribedMealBody,
): Promise<Entry> {
  return send<Entry>(`/api/me/nutrition/${date}/describe-meal`, "POST", body);
}

/**
 * Fire-and-forget describe: the backend logs an ANALYZING placeholder named
 * with the description and returns it immediately (202); resolution runs
 * server-side and the day view polls it in.
 */
export function describeMealAsync(
  date: string,
  body: LogDescribedMealBody,
): Promise<Entry> {
  return send<Entry>(`/api/me/nutrition/${date}/describe-meal-async`, "POST", body);
}

/**
 * Distinct foods/meals logged in the last `days` days, deduped, newest first —
 * the add-flow's one-tap "recent meals" list. Each row carries `date` + ids
 * needed to re-log it via {@link relogEntry}.
 */
export function getRecentMeals(
  days = 14,
  limit = 20,
  meal?: Meal,
): Promise<Entry[]> {
  const mealParam = meal ? `&meal=${meal}` : "";
  return apiJson<Entry[]>(
    `/api/me/nutrition/recent-meals?days=${days}&limit=${limit}${mealParam}`,
  );
}

/** One-tap re-log: server-side copy of a past entry onto `date` (no AI rework). */
export function relogEntry(date: string, body: RelogBody): Promise<Entry> {
  return send<Entry>(`/api/me/nutrition/${date}/relog`, "POST", body);
}
