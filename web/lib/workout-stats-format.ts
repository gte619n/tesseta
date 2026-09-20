// Pure helpers for laying out the Overview's consistency heatmap and formatting
// stats values. Kept framework-free so the layout math is unit-testable without
// rendering (IMPL-WEB-WORKOUT-01 §6). All date math is done in UTC on date-only
// strings so it never drifts with the test runner's zone.

import type { HeatmapDay, SessionRef } from "./types/workout-stats";

function parseISO(d: string): Date {
  return new Date(`${d}T00:00:00Z`);
}

function toISO(d: Date): string {
  return d.toISOString().slice(0, 10);
}

export function addDays(iso: string, n: number): string {
  const d = parseISO(iso);
  d.setUTCDate(d.getUTCDate() + n);
  return toISO(d);
}

// Monday of the ISO week containing `iso` (Monday-start weeks).
export function mondayOf(iso: string): string {
  const d = parseISO(iso);
  const mondayIndex = (d.getUTCDay() + 6) % 7; // Sun=0 → 6, Mon=1 → 0, …
  return addDays(iso, -mondayIndex);
}

// Whole ISO weeks from `fromIso` to `toIso` (floored, by Monday-of-week), or 0
// when `toIso` is on/before `fromIso`'s week.
export function weeksBetween(fromIso: string, toIso: string): number {
  const from = parseISO(mondayOf(fromIso)).getTime();
  const to = parseISO(mondayOf(toIso)).getTime();
  if (to <= from) return 0;
  return Math.floor((to - from) / (7 * 24 * 60 * 60 * 1000));
}

export type HeatmapCell = {
  date: string;
  count: number;
  ref: SessionRef | null;
  future: boolean;
};

export type HeatmapColumn = {
  weekStart: string;
  cells: HeatmapCell[]; // 7, Monday→Sunday
};

/**
 * A weeks-wide grid (one column per ISO week, Monday→Sunday rows) ending with
 * the week containing `todayIso`. Cells after today are flagged {@code future};
 * days with sessions carry their count + a deep-link ref.
 */
export function buildHeatmapGrid(
  days: HeatmapDay[],
  todayIso: string,
  weeks: number,
): HeatmapColumn[] {
  const byDate = new Map<string, HeatmapDay>();
  for (const d of days) byDate.set(d.date, d);

  const start = addDays(mondayOf(todayIso), -(weeks - 1) * 7);
  const columns: HeatmapColumn[] = [];
  for (let w = 0; w < weeks; w++) {
    const weekStart = addDays(start, w * 7);
    const cells: HeatmapCell[] = [];
    for (let day = 0; day < 7; day++) {
      const date = addDays(weekStart, day);
      const hit = byDate.get(date);
      cells.push({
        date,
        count: hit?.sessionCount ?? 0,
        ref: hit?.first ?? null,
        future: date > todayIso,
      });
    }
    columns.push({ weekStart, cells });
  }
  return columns;
}

// Tailwind class for a heatmap cell's fill, ramping with session count.
export function heatmapIntensityClass(cell: HeatmapCell): string {
  if (cell.future) return "bg-transparent";
  if (cell.count <= 0) return "bg-border-subtle/60";
  if (cell.count === 1) return "bg-accent/40";
  if (cell.count === 2) return "bg-accent/70";
  return "bg-accent";
}
