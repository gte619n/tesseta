// Mapping between the chat proposal (a WorkoutProgramDeepResponse streamed on
// the `proposal` SSE event) and the CreateProgramRequest the commit endpoint
// accepts. Import-safe from client and server (no apiFetch, no server env).

import type {
  WorkoutProgramDeepResponse,
  CreateProgramRequest,
  PhaseInput,
  DayInput,
  BlockInput,
  PrescriptionInput,
  ScheduleInput,
  WeekDay,
} from "./types/workout-program";

export type ChatRole = "user" | "assistant";

export type ChatHistoryEntry = {
  role: ChatRole;
  content: string;
};

// Build the schedule from the deep proposal: training days and the gym per day
// are derived from the phases' workout days (the proposal carries them inline).
function deriveSchedule(proposal: WorkoutProgramDeepResponse): ScheduleInput {
  const dayLocations: Partial<Record<WeekDay, string>> = {};
  const trainingDays: WeekDay[] = [];
  for (const phase of proposal.phases) {
    for (const day of phase.days) {
      if (!trainingDays.includes(day.dayOfWeek)) trainingDays.push(day.dayOfWeek);
      dayLocations[day.dayOfWeek] = day.locationId;
    }
  }
  // Prefer the program's declared training days when present.
  const declared = proposal.trainingDays ?? [];
  return {
    trainingDays: declared.length > 0 ? declared : trainingDays,
    dayLocations,
  };
}

export function proposalToCreateRequest(
  proposal: WorkoutProgramDeepResponse,
): CreateProgramRequest {
  const phases: PhaseInput[] = proposal.phases.map((phase) => {
    const days: DayInput[] = phase.days.map((day) => {
      const blocks: BlockInput[] = day.blocks.map((block) => {
        const prescriptions: PrescriptionInput[] = block.prescriptions.map((p) => ({
          exerciseId: p.exerciseId,
          sets: p.sets,
          repsMin: p.repsMin,
          repsMax: p.repsMax,
          durationSeconds: p.durationSeconds,
          intensity: p.intensity,
          restSeconds: p.restSeconds,
          tempo: p.tempo,
          notes: p.notes,
          deloadModifier: p.deloadModifier,
        }));
        return { type: block.type, title: block.title, prescriptions };
      });
      return {
        label: day.label,
        dayOfWeek: day.dayOfWeek,
        locationId: day.locationId,
        blocks,
      };
    });
    return {
      title: phase.title,
      focus: phase.focus,
      weeks: phase.weeks,
      deloadWeekIndex: phase.deloadWeekIndex,
      days,
    };
  });

  return {
    title: proposal.title,
    description: proposal.description,
    goalId: proposal.goalId,
    schedule: deriveSchedule(proposal),
    startDate: proposal.startDate,
    source: proposal.source ?? "AI_GENERATED",
    phases,
  };
}
