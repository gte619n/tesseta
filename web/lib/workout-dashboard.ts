import { getWorkoutHistorySummary, listPrograms } from "./workout-program-api";

// Dashboard loader for the Workout card — lightweight training summary (total
// sessions completed, when the last one was, and the active program). Uses the
// same cheap counts endpoint the Workouts hub uses; errors degrade to null so
// the card renders a graceful empty state rather than failing the page.

export type WorkoutSummary = {
  totalCount: number;
  lastWorkoutDate: string | null;
  activeProgramTitle: string | null;
  activeProgramCount: number;
};

export async function loadWorkoutSummary(): Promise<WorkoutSummary | null> {
  try {
    const [summary, programs] = await Promise.all([
      getWorkoutHistorySummary(),
      // The program list is a nice-to-have; a failure there shouldn't blank the
      // whole card, so it degrades to "no active program".
      listPrograms().catch(() => []),
    ]);
    const active = programs.filter((p) => p.status === "ACTIVE");
    return {
      totalCount: summary.count,
      lastWorkoutDate: summary.lastWorkoutDate,
      activeProgramTitle: active[0]?.title ?? null,
      activeProgramCount: active.length,
    };
  } catch {
    return null;
  }
}
