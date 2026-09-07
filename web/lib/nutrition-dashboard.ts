import { getDay } from "./nutrition-api";
import type { Macros } from "./types/nutrition";

// Dashboard loader for the Nutrition card — today's macro totals against the
// active target. Mirrors the pattern of the other dashboard loaders
// (lib/blood-panel, lib/body-composition-dashboard): all backend calls live
// here, errors degrade to null so the card renders a graceful empty state
// instead of taking down the page.

export type NutritionSummary = {
  date: string;
  totals: Macros;
  target: Macros | null;
};

// Local calendar date as yyyy-MM-dd — same derivation the Nutrition page uses.
function today(): string {
  return new Date().toISOString().split("T")[0] ?? "";
}

export async function loadTodayNutrition(): Promise<NutritionSummary | null> {
  try {
    // getDay already joins in the active target, so no separate target fetch.
    const day = await getDay(today());
    return { date: day.date, totals: day.totals, target: day.target };
  } catch {
    return null;
  }
}
