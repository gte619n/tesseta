import Link from "next/link";
import type { Route } from "next";
import type { PrPoint } from "@/lib/types/workout-stats";
import { formatNumber } from "@/lib/format-number";
import { formatDateUpper } from "@/lib/format-date";
import { isPerHand } from "@/lib/per-hand";

// Recent personal records (IMPL-WEB-WORKOUT-01 D21): the last few PR sessions,
// each linking to its session detail. A PR is a new best estimated 1RM for a
// lift; the row shows the achieving set. Pure presentational.

export function RecentPrsCard({ prs }: { prs: PrPoint[] }) {
  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
        Recent PRs
      </div>

      {prs.length === 0 ? (
        <p className="mt-4 text-[13px] text-secondary">
          No personal records yet. New bests show up here.
        </p>
      ) : (
        <ul className="mt-3 divide-y divide-border-subtle" data-testid="recent-prs">
          {prs.map((pr) => (
            <li key={`${pr.exerciseId}:${pr.date}`} data-testid="pr-row">
              <Link
                href={`/me/workouts/history/${pr.programId}/${pr.scheduledId}` as Route}
                className="flex items-center justify-between gap-3 rounded-[6px] px-1 py-2.5 transition-colors hover:bg-canvas"
              >
                <div className="min-w-0">
                  <div className="truncate text-[13px] font-medium text-primary">
                    {pr.exerciseName}
                  </div>
                  <div className="mt-0.5 font-mono text-[11px] text-tertiary tabular-nums">
                    {formatDateUpper(pr.date)}
                  </div>
                </div>
                <div className="shrink-0 text-right">
                  <div className="font-mono text-[13px] font-medium text-accent-dim tabular-nums">
                    {formatNumber(pr.e1rmTotalLbs ?? pr.e1rmLbs)} lb
                  </div>
                  {pr.reps != null && (
                    <div className="font-mono text-[10px] text-tertiary tabular-nums">
                      {formatNumber(pr.weightTotalLbs ?? pr.weightLbs)} × {pr.reps}
                      {isPerHand(pr.loadFactor) && (
                        <span className="ml-1">
                          ({formatNumber((pr.weightTotalLbs ?? pr.weightLbs) / 2)}/hand)
                        </span>
                      )}
                    </div>
                  )}
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
