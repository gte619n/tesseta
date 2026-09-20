import { getWorkoutHistorySummary, listPrograms } from "./workout-program-api";
import { getWorkoutStats } from "./workout-stats-api";

// Dashboard loader for the Workout card — lightweight training summary (total
// sessions completed, when the last one was, the active program, and the streak
// headline). Uses the same cheap counts endpoint the Workouts hub uses; errors
// degrade to null so the card renders a graceful empty state rather than failing
// the page. The streak rides the new stats endpoint and degrades to null on its
// own so an undeployed backend just drops the streak line (IMPL-WEB-WORKOUT-01
// D23 / IL-11).

export type WorkoutSummary = {
  totalCount: number;
  lastWorkoutDate: string | null;
  activeProgramTitle: string | null;
  activeProgramCount: number;
  streak: {
    current: number;
    thisWeekCompleted: number;
    weeklyTarget: number;
  } | null;
};

export async function loadWorkoutSummary(): Promise<WorkoutSummary | null> {
  try {
    const [summary, programs, stats] = await Promise.all([
      getWorkoutHistorySummary(),
      // The program list is a nice-to-have; a failure there shouldn't blank the
      // whole card, so it degrades to "no active program".
      listPrograms().catch(() => []),
      // The streak is additive; a missing/undeployed stats endpoint just drops
      // the streak line rather than blanking the card.
      getWorkoutStats().catch(() => null),
    ]);
    const active = programs.filter((p) => p.status === "ACTIVE");
    return {
      totalCount: summary.count,
      lastWorkoutDate: summary.lastWorkoutDate,
      activeProgramTitle: active[0]?.title ?? null,
      activeProgramCount: active.length,
      streak: stats
        ? {
            current: stats.streak.current,
            thisWeekCompleted: stats.streak.thisWeekCompleted,
            weeklyTarget: stats.streak.weeklyTarget,
          }
        : null,
    };
  } catch {
    return null;
  }
}
