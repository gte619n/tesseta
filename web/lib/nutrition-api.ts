import { apiFetch, apiJson, BackendError } from "./api";
import type {
  NutritionDay,
  Macros,
  Entry,
  Food,
  DailyRollup,
  AddEntryBody,
  UpdateEntryBody,
  CreateFoodBody,
} from "./types/nutrition";

// Server-only HTTP helpers for the Nutrition module (IMPL-13).
// Do NOT import from "use client" components — apiFetch reads server env
// + the Auth.js session. Define mutations as inline server actions in
// server pages and pass them down as props.

// ── Internal request helper (mirrors goals-api.ts) ───────────────────

async function send<T>(
  path: string,
  method: "POST" | "PATCH" | "PUT" | "DELETE",
  body?: unknown,
): Promise<T> {
  const res = await apiFetch(path, {
    method,
    ...(body !== undefined
      ? {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        }
      : {}),
  });
  if (!res.ok) {
    throw new BackendError(`${method} ${path} returned ${res.status}`, res.status);
  }
  // 204 / empty body responses (DELETE) have nothing to parse.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

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

/** Create a new catalog food (manual / AI-confirmed). */
export function createFood(body: CreateFoodBody): Promise<Food> {
  return send<Food>("/api/foods", "POST", body);
}
