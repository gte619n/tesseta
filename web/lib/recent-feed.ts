import { apiJson } from "@/lib/api";
import { fetchDailyMetrics } from "@/lib/daily-metrics-api";
import { getWorkoutHistory } from "@/lib/workout-program-api";
import type { ScheduledWorkoutResponse } from "@/lib/types/workout-program";
import type { Reading } from "@/lib/types/body-composition";
import type { TodaysDose } from "@/lib/types/medication";
import { formatDuration, totalLoggedSets } from "@/lib/workout-format";
import { kgToLb } from "@/lib/units";

// One row in the dashboard's "Recent" activity feed. (Was a fixture type; the
// feed is now built from real data — see loadRecentFeed.)
export type LogEntry = {
  icon: string;
  tone: "activity" | "neutral";
  title: string;
  meta?: string;
  metaPhone?: string;
  metaFoldable?: string;
  time: string;
};

type Item = LogEntry & { ts: number };

const MAX_ENTRIES = 5;

/**
 * Build the dashboard "Recent" feed by merging the user's latest real activity
 * across four sources — completed workouts, weigh-ins, sleep, and medication
 * doses taken today — sorted newest-first. Every source degrades to empty on
 * error, so a partial outage just thins the feed rather than breaking the page.
 */
export async function loadRecentFeed(): Promise<LogEntry[]> {
  const [workouts, readings, metrics, doses] = await Promise.all([
    safe(getWorkoutHistory()),
    safe(apiJson<Reading[]>("/api/me/body-composition")),
    fetchDailyMetrics(), // already returns [] on error
    safe(apiJson<TodaysDose[]>("/api/me/medications/today")),
  ]);

  const items: Item[] = [
    ...workoutItems(workouts),
    ...weighInItems(readings),
    ...sleepItems(metrics),
    ...medItems(doses),
  ];

  // Item extends LogEntry with an internal `ts` sort key; it's structurally a
  // LogEntry, so the slice is returned as-is (the extra field is ignored by the
  // ts-agnostic RecentFeed).
  items.sort((a, b) => b.ts - a.ts);
  return items.slice(0, MAX_ENTRIES);
}

function workoutItems(workouts: ScheduledWorkoutResponse[]): Item[] {
  return workouts
    .filter((w) => w.completedAt)
    .map((w) => {
      const ts = Date.parse(w.completedAt!);
      const parts: string[] = [];
      if (w.durationSeconds) parts.push(formatDuration(w.durationSeconds));
      const sets = totalLoggedSets(w);
      if (sets > 0) parts.push(`${sets} sets`);
      return {
        icon: "barbell",
        tone: "activity" as const,
        title: `${w.dayLabel} completed`,
        ...(parts.length ? { meta: parts.join(" · ") } : {}),
        time: relTime(ts),
        ts,
      };
    });
}

function weighInItems(readings: Reading[]): Item[] {
  return readings
    .filter((r) => r.metric === "WEIGHT_KG")
    .map((r) => {
      const ts = Date.parse(r.sampleTime);
      const lb = kgToLb(r.value);
      return {
        icon: "scale",
        tone: "neutral" as const,
        title: `Weighed in · ${lb.toFixed(1)} lb`,
        ...(r.sourcePlatform ? { meta: r.sourcePlatform } : {}),
        time: relTime(ts),
        ts,
      };
    });
}

function sleepItems(metrics: { date: string; sleepMinutes: number | null; sleepScore: number | null }[]): Item[] {
  return metrics
    .filter((m) => m.sleepMinutes !== null)
    .map((m) => {
      const ts = Date.parse(`${m.date}T00:00:00`);
      const mins = m.sleepMinutes!;
      const h = Math.floor(mins / 60);
      const min = Math.round(mins % 60);
      return {
        icon: "moon",
        tone: "neutral" as const,
        title: `Sleep · ${h}h ${min}m`,
        ...(m.sleepScore !== null ? { meta: `Score ${m.sleepScore}` } : {}),
        time: relTime(ts),
        ts,
      };
    });
}

function medItems(doses: TodaysDose[]): Item[] {
  return doses
    .filter((d) => d.taken && d.takenAt)
    .map((d) => {
      const ts = Date.parse(d.takenAt!);
      return {
        icon: "pill",
        tone: "activity" as const,
        title: `${d.drugName} · ${d.dose} ${d.unit}`,
        time: relTime(ts),
        ts,
      };
    });
}

async function safe<T>(p: Promise<T[]>): Promise<T[]> {
  try {
    return await p;
  } catch {
    return [];
  }
}

// Compact "time ago" label for the right-hand column: now / 12m / 3h / 2d, or a
// short date once it's older than a week.
function relTime(ts: number, now: number = Date.now()): string {
  const min = Math.floor((now - ts) / 60_000);
  if (min < 1) return "now";
  if (min < 60) return `${min}m`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}h`;
  const day = Math.floor(hr / 24);
  if (day < 7) return `${day}d`;
  return new Date(ts)
    .toLocaleDateString("en-US", { month: "short", day: "numeric" })
    .toUpperCase();
}
