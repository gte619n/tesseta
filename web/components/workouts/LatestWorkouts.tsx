import Link from "next/link";
import type { Route } from "next";
import type { ScheduledWorkoutResponse } from "@/lib/types/workout-program";
import { formatDateUpper } from "@/lib/format-date";

// Compact "latest workouts" list for the Overview (IMPL-WEB-WORKOUT-01 D10):
// one line per recent session — date, day label, sets logged, duration, feeling
// — each linking through to the session detail page. Deeper detail lives there;
// this stays scannable.

const FEELING_EMOJI = ["", "😖", "😕", "😐", "🙂", "💪"]; // 1..5

export function countLoggedSets(session: ScheduledWorkoutResponse): number {
  let n = 0;
  for (const block of session.session?.blocks ?? []) {
    for (const rx of block.prescriptions ?? []) {
      n += rx.loggedSets?.length ?? 0;
    }
  }
  return n;
}

function durationLabel(seconds: number | null): string | null {
  if (!seconds || seconds <= 0) return null;
  const mins = Math.round(seconds / 60);
  return `${mins} min`;
}

export function LatestWorkouts({
  sessions,
}: {
  sessions: ScheduledWorkoutResponse[];
}) {
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="flex items-center justify-between">
        <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
          Latest workouts
        </div>
        <Link
          href="/me/workouts/history"
          className="font-mono text-[11px] text-tertiary hover:text-accent-dim"
        >
          History →
        </Link>
      </div>

      {sessions.length === 0 ? (
        <p className="mt-4 text-[13px] text-secondary">
          No workouts logged yet.
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-border-subtle" data-testid="latest-workouts">
          {sessions.map((s) => {
            const setCount = countLoggedSets(s);
            const duration = durationLabel(s.durationSeconds);
            const feeling =
              s.feeling && s.feeling >= 1 && s.feeling <= 5
                ? FEELING_EMOJI[s.feeling]
                : null;
            const href =
              s.programId != null
                ? `/me/workouts/history/${s.programId}/${s.scheduledId}`
                : null;
            const row = (
              <div className="flex items-center justify-between py-2.5">
                <div className="min-w-0">
                  <div className="text-[13px] font-medium text-primary">
                    {s.dayLabel}
                  </div>
                  <div className="mt-0.5 font-mono text-[11px] text-tertiary tabular-nums">
                    {formatDateUpper(s.date)}
                  </div>
                </div>
                <div className="flex shrink-0 items-center gap-3 text-[11px] text-tertiary">
                  <span className="tabular-nums">
                    {setCount} set{setCount !== 1 && "s"}
                  </span>
                  {duration && <span className="tabular-nums">{duration}</span>}
                  {feeling && <span aria-hidden>{feeling}</span>}
                </div>
              </div>
            );
            return (
              <li key={`${s.programId ?? ""}:${s.scheduledId}`} data-testid="latest-workout-row">
                {href ? (
                  <Link
                    href={href as Route}
                    className="block rounded-[6px] px-1 transition-colors hover:bg-canvas"
                  >
                    {row}
                  </Link>
                ) : (
                  <div className="px-1">{row}</div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
