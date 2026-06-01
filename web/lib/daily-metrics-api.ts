import { apiJson } from "@/lib/api";

// Server-only helper for the daily-metrics endpoint. Mirrors the backend
// DailyMetricResponse shape: one row per calendar day in the requested
// range, with any field nullable for a given day.
export type DailyMetric = {
  date: string; // YYYY-MM-DD
  steps: number | null;
  restingHeartRate: number | null;
  sleepMinutes: number | null;
  hrvMs: number | null;
  sleepScore: number | null;
};

const DAY_MS = 24 * 60 * 60 * 1000;

function isoDate(d: Date): string {
  return d.toISOString().split("T")[0]!;
}

// Fetches the last 14 days of daily metrics. The dashboard only needs ~9-14
// points to compute deltas/sparklines, so a 14-day window avoids over-fetching
// the previously-requested 30 days. Returns [] on any error so the dashboard
// server component can fall back gracefully without crashing.
export async function fetchDailyMetrics(): Promise<DailyMetric[]> {
  const now = Date.now();
  const to = isoDate(new Date(now));
  const from = isoDate(new Date(now - 14 * DAY_MS));
  try {
    return await apiJson<DailyMetric[]>(
      `/api/me/daily-metrics?from=${from}&to=${to}`,
    );
  } catch {
    return [];
  }
}
