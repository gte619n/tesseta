import Link from "next/link";
import type { Route } from "next";
import type { HeatmapDay } from "@/lib/types/workout-stats";
import { heatmapIntensityClass } from "@/lib/workout-stats-format";

// Calendar-based consistency view (IMPL-WEB-WORKOUT-01 D5): the last 6 months as
// real month calendars (weeks as rows, Mon→Sun columns), spaced apart, with
// workout days tinted by session count and deep-linked to the session. Replaces
// the earlier contribution-graph. Server component — pure date math.

const MONTHS_SHOWN = 2;
const MONTH_NAMES = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const WEEKDAY_INITIALS = ["S", "M", "T", "W", "T", "F", "S"]; // Sunday-first

type Cell = {
  date: string;
  day: number;
  count: number;
  ref: { programId: string; scheduledId: string } | null;
  future: boolean;
} | null;

function pad(n: number): string {
  return n < 10 ? `0${n}` : `${n}`;
}

function buildMonth(
  year: number,
  month0: number,
  byDate: Map<string, HeatmapDay>,
  todayIso: string,
): { label: string; year: number; cells: Cell[] } {
  const firstDow = new Date(Date.UTC(year, month0, 1)).getUTCDay(); // Sun=0
  const daysIn = new Date(Date.UTC(year, month0 + 1, 0)).getUTCDate();
  const cells: Cell[] = [];
  for (let i = 0; i < firstDow; i++) cells.push(null); // leading blanks
  for (let d = 1; d <= daysIn; d++) {
    const date = `${year}-${pad(month0 + 1)}-${pad(d)}`;
    const hit = byDate.get(date);
    cells.push({
      date,
      day: d,
      count: hit?.sessionCount ?? 0,
      ref: hit?.first ?? null,
      future: date > todayIso,
    });
  }
  while (cells.length % 7 !== 0) cells.push(null); // trailing blanks
  return { label: MONTH_NAMES[month0]!, year, cells };
}

export function ConsistencyHeatmap({
  days,
  today,
}: {
  days: HeatmapDay[];
  today: string;
}) {
  const byDate = new Map(days.map((d) => [d.date, d]));

  const now = new Date(`${today}T00:00:00Z`);
  const months: { label: string; year: number; cells: Cell[] }[] = [];
  for (let i = MONTHS_SHOWN - 1; i >= 0; i--) {
    const d = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - i, 1));
    months.push(buildMonth(d.getUTCFullYear(), d.getUTCMonth(), byDate, today));
  }

  // Workout days within the shown window (first day of the earliest shown month
  // through today).
  const shownStart = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth() - (MONTHS_SHOWN - 1), 1))
    .toISOString()
    .slice(0, 10);
  const totalDays = days.filter((d) => d.date >= shownStart && d.date <= today).length;

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-center justify-between">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Consistency
        </div>
        <div className="font-mono text-[11px] text-tertiary tabular-nums">
          {totalDays} day{totalDays !== 1 && "s"} · 2 mo
        </div>
      </div>

      {totalDays === 0 ? (
        <p className="mt-4 text-[13px] text-secondary">
          No workouts logged yet. Completed sessions light up here.
        </p>
      ) : (
        <div
          className="mt-4 grid grid-cols-1 gap-x-10 gap-y-6 sm:grid-cols-2"
          data-testid="heatmap-grid"
        >
          {months.map((m) => (
            <div key={`${m.year}-${m.label}`}>
              <div className="mb-1.5 text-[12px] font-medium text-primary">
                {m.label} <span className="text-tertiary">{m.year}</span>
              </div>
              <div className="grid grid-cols-7 gap-[3px]">
                {WEEKDAY_INITIALS.map((w, i) => (
                  <div
                    key={`h${i}`}
                    className="text-center font-mono text-[8px] leading-none text-tertiary"
                  >
                    {w}
                  </div>
                ))}
                {m.cells.map((cell, i) => {
                  if (!cell) return <div key={`b${i}`} className="aspect-square" aria-hidden />;
                  const cls =
                    "flex aspect-square items-center justify-center rounded-[3px] text-[9px] tabular-nums " +
                    heatmapIntensityClass(cell) +
                    (cell.count > 0 ? " font-medium text-white" : " text-tertiary");
                  if (cell.ref && cell.count > 0) {
                    return (
                      <Link
                        key={cell.date}
                        href={`/me/workouts/history/${cell.ref.programId}/${cell.ref.scheduledId}` as Route}
                        aria-label={`${cell.count} workout${cell.count !== 1 ? "s" : ""} on ${cell.date}`}
                        title={`${cell.date}: ${cell.count} workout${cell.count !== 1 ? "s" : ""}`}
                        data-testid="heatmap-day"
                        data-date={cell.date}
                        className={cls + " transition-transform hover:scale-110"}
                      >
                        {cell.day}
                      </Link>
                    );
                  }
                  return (
                    <div
                      key={cell.date}
                      className={cls}
                      data-testid="heatmap-day"
                      data-date={cell.date}
                    >
                      {cell.day}
                    </div>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
