import type { WorkoutStreak } from "@/lib/types/workout-stats";

// The Overview's headline: the consecutive-weeks streak, this-week progress
// toward the weekly target, and the all-time best (IMPL-WEB-WORKOUT-01 D9).
// Pure presentational — the streak is computed server-side; this only displays
// it, so a fixture renders the exact numbers under test.

export function StreakHero({ streak }: { streak: WorkoutStreak | null }) {
  const current = streak?.current ?? 0;
  const target = streak?.weeklyTarget ?? 0;
  const thisWeek = streak?.thisWeekCompleted ?? 0;
  const longest = streak?.longest ?? 0;
  const progressPct = target > 0 ? Math.min(100, (thisWeek / target) * 100) : 0;
  const metThisWeek = target > 0 && thisWeek >= target;

  return (
    <div className="rounded-[14px] border-[0.5px] border-border-default bg-surface px-6 py-5">
      <div className="caps-mono text-[9px] tracking-[0.08em] text-tertiary">
        Weekly streak
      </div>
      <div className="mt-2 flex items-baseline gap-2">
        <span
          className="font-mono text-[44px] font-medium leading-none tracking-[-0.02em] text-primary tabular-nums"
          data-testid="streak-current"
        >
          {current}
        </span>
        <span className="text-[14px] text-secondary">
          {current === 1 ? "week" : "weeks"}
        </span>
      </div>

      <div className="mt-5">
        <div className="flex items-center justify-between text-[12px]">
          <span className="text-secondary">This week</span>
          <span className="font-mono text-tertiary tabular-nums" data-testid="streak-this-week">
            {thisWeek}/{target}
          </span>
        </div>
        <div
          className="mt-1.5 h-2 w-full overflow-hidden rounded-full bg-border-subtle"
          role="progressbar"
          aria-valuenow={thisWeek}
          aria-valuemin={0}
          aria-valuemax={target}
        >
          <div
            className={
              "h-full rounded-full transition-all " +
              (metThisWeek ? "bg-accent" : "bg-accent/60")
            }
            style={{ width: `${progressPct}%` }}
          />
        </div>
        <p className="mt-2 text-[12px] text-secondary">
          {metThisWeek
            ? "Target met — this week is keeping the streak alive."
            : target > 0
              ? `${Math.max(0, target - thisWeek)} more this week to keep the streak.`
              : "Set a weekly target in Preferences."}
        </p>
      </div>

      <div className="mt-4 border-t border-border-subtle pt-3">
        <div className="flex items-center gap-2 text-[12px] text-tertiary">
          <span className="font-medium text-primary tabular-nums" data-testid="streak-longest">
            {longest}
          </span>
          <span>longest streak (weeks)</span>
        </div>
      </div>
    </div>
  );
}
