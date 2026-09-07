import Link from "next/link";
import type { WorkoutSummary } from "@/lib/workout-dashboard";
import { formatWholeNumber } from "@/lib/format-number";
import { formatObserved } from "@/lib/format-observed";

// Compact dashboard card: training at a glance — total sessions completed, the
// active program, and when the last workout was. Server component.

export function WorkoutCard({ summary }: { summary: WorkoutSummary | null }) {
  return (
    <div className="rounded-[10px] border-[0.5px] border-border-default bg-surface px-5 py-[18px]">
      <div className="flex items-center justify-between">
        <Link
          href="/me/workouts"
          className="group inline-flex items-center gap-2.5 hover:text-accent-dim"
        >
          <span
            aria-hidden
            className="inline-block h-3.5 w-[3px] rounded-[2px] bg-accent"
          />
          <span className="text-[14px] font-medium tracking-[-0.01em] text-primary group-hover:text-accent-dim">
            Workouts
          </span>
          <span
            aria-hidden
            className="font-mono text-[11px] text-tertiary opacity-0 transition-opacity group-hover:opacity-100"
          >
            →
          </span>
        </Link>
        {summary && summary.lastWorkoutDate && (
          <span className="font-mono text-[11px] text-tertiary tabular-nums">
            last {formatObserved(summary.lastWorkoutDate)}
          </span>
        )}
      </div>

      {summary === null ? (
        <p className="mt-3 text-[13px] text-secondary">
          Couldn&apos;t load your workouts.
        </p>
      ) : (
        <>
          <div className="mt-3 flex items-baseline gap-2">
            <span className="font-mono text-[28px] font-medium leading-none tracking-[-0.02em] text-primary tabular-nums">
              {formatWholeNumber(summary.totalCount)}
            </span>
            <span className="font-mono text-[12px] text-tertiary">
              {summary.totalCount === 1 ? "session logged" : "sessions logged"}
            </span>
          </div>

          <div className="mt-4 border-t border-border-subtle pt-3">
            <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
              Active program
            </div>
            <div className="mt-1 text-[13px] text-primary">
              {summary.activeProgramTitle ? (
                <>
                  <span className="truncate">{summary.activeProgramTitle}</span>
                  {summary.activeProgramCount > 1 && (
                    <span className="ml-1.5 font-mono text-[11px] text-tertiary">
                      +{summary.activeProgramCount - 1} more
                    </span>
                  )}
                </>
              ) : (
                <span className="text-secondary">No active program</span>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
