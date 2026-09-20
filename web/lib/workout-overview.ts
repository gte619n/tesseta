import type { CurrentProgramView } from "@/components/workouts/CurrentProgramCard";
import type {
  ScheduledWorkoutResponse,
  WorkoutProgramDeepResponse,
} from "./types/workout-program";
import { weeksBetween } from "./workout-stats-format";

// Pure assembly of the Overview's current-program view-model from the deep
// program + a near-term calendar window. Kept framework-free so the phase/week
// math and next-session pick are unit-testable (IMPL-WEB-WORKOUT-01 §4).

/**
 * Build the {@link CurrentProgramView} for the active program: the ACTIVE phase
 * (title, 1-based number, count), the current week within that phase (derived
 * from the phase's target start vs. today, clamped to the phase length), and the
 * next scheduled session on/after today.
 */
export function buildCurrentProgramView(
  deep: WorkoutProgramDeepResponse,
  calendar: ScheduledWorkoutResponse[],
  todayIso: string,
): CurrentProgramView {
  const phases = deep.phases ?? [];
  const activeIdx = phases.findIndex((p) => p.status === "ACTIVE");
  const active = activeIdx >= 0 ? phases[activeIdx] : null;

  let weekInPhase: number | null = null;
  let weeksInPhase: number | null = null;
  if (active) {
    weeksInPhase = active.weeks;
    const elapsed = active.targetStartDate
      ? weeksBetween(active.targetStartDate, todayIso)
      : 0;
    weekInPhase = Math.max(1, Math.min(active.weeks, elapsed + 1));
  }

  const upcoming = calendar
    .filter((s) => s.status === "PLANNED" && s.date >= todayIso)
    .sort((a, b) => a.date.localeCompare(b.date));
  const next = upcoming[0] ?? null;

  return {
    programId: deep.programId,
    title: deep.title,
    trainingDays: deep.trainingDays ?? [],
    phaseTitle: active?.title ?? null,
    phaseNumber: activeIdx >= 0 ? activeIdx + 1 : null,
    phaseCount: phases.length > 0 ? phases.length : null,
    weekInPhase,
    weeksInPhase,
    nextSession: next
      ? {
          programId: next.programId ?? deep.programId,
          scheduledId: next.scheduledId,
          date: next.date,
          dayLabel: next.dayLabel,
        }
      : null,
  };
}
