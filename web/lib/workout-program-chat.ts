// Mapping between the chat proposal (a WorkoutProgramDeepResponse streamed on
// the `proposal` SSE event / restored from a thread's proposalJson) and the
// editable draft tree the WorkoutProgramProposalCard works in, plus the
// CreateProgramRequest the commit endpoint accepts. Import-safe from client and
// server (no apiFetch, no server env).

import type {
  WorkoutProgramDeepResponse,
  CreateProgramRequest,
  PhaseInput,
  DayInput,
  BlockInput,
  PrescriptionInput,
  ScheduleInput,
  WeekDay,
  Intensity,
  DeloadModifier,
  PrescriptionExercise,
  WorkoutProgramChatSchedule,
  NutritionGuidance,
} from "./types/workout-program";
import type { BlockType } from "./types/exercise";

// ── Local render-key generation ──────────────────────────────────────

let keySeq = 0;
function nextKey(prefix: string): string {
  keySeq += 1;
  return `${prefix}-${keySeq}-${Math.random().toString(36).slice(2, 7)}`;
}

// ── Editable draft tree ──────────────────────────────────────────────
//
// Mirrors the deep structure but with stable `key`s for React list identity and
// looser field types so partially-edited rows are representable. The embedded
// `exercise` summary is kept for display (name) on already-chosen rows.

export type PrescriptionDraft = {
  key: string;
  exerciseId: string;
  exercise: PrescriptionExercise | null;
  sets: number | null;
  repsMin: number | null;
  repsMax: number | null;
  durationSeconds: number | null;
  intensity: Intensity;
  // Concrete prescribed load (IMPL-18); null → fall back to `intensity`.
  targetWeightLbs: number | null;
  // History basis for the weight, surfaced on the "why" affordance. Read-only.
  loadBasis: string | null;
  restSeconds: number | null;
  tempo: string | null;
  notes: string | null;
  deloadModifier: DeloadModifier | null;
  // Inline validator note from the backend (e.g. "not executable at this gym").
  validationError: string | null;
};

export type BlockDraft = {
  key: string;
  type: BlockType;
  title: string;
  prescriptions: PrescriptionDraft[];
};

export type DayDraft = {
  key: string;
  label: string;
  dayOfWeek: WeekDay;
  locationId: string;
  locationName: string;
  blocks: BlockDraft[];
};

export type PhaseDraft = {
  key: string;
  title: string;
  focus: string;
  weeks: number;
  deloadWeekIndex: number | null;
  days: DayDraft[];
  // Per-phase nutrition target (IMPL-18). Display-only in the card, but carried
  // through so commit round-trips it.
  nutritionGuidance: NutritionGuidance | null;
};

export type ProgramProposalDraft = {
  title: string;
  description: string;
  goalId: string | null;
  startDate: string;
  source: WorkoutProgramDeepResponse["source"];
  phases: PhaseDraft[];
  // Program-level nutrition fallback (IMPL-18). Display-only; round-tripped.
  nutritionGuidance: NutritionGuidance | null;
  // Validator notes that aren't tied to a specific prescription.
  issues: string[];
  // IMPL-18: soft, override-able advisories (volume/deload/ramp) — shown but
  // never block a commit (R1).
  warnings: string[];
};

// ── Deep proposal → editable draft ───────────────────────────────────

function emptyIntensity(i: Intensity | null): Intensity {
  if (i) return { kind: i.kind, value: i.value };
  return { kind: "NONE", value: null };
}

export function proposalToDraft(
  program: WorkoutProgramDeepResponse,
  issues: string[],
  warnings: string[] = [],
): ProgramProposalDraft {
  const phases: PhaseDraft[] = program.phases.map((phase) => {
    const days: DayDraft[] = phase.days.map((day) => {
      const blocks: BlockDraft[] = day.blocks.map((block) => {
        const prescriptions: PrescriptionDraft[] = block.prescriptions.map(
          (p) => ({
            key: nextKey("rx"),
            exerciseId: p.exerciseId,
            exercise: p.exercise,
            sets: p.sets,
            repsMin: p.repsMin,
            repsMax: p.repsMax,
            durationSeconds: p.durationSeconds,
            intensity: emptyIntensity(p.intensity),
            targetWeightLbs: p.targetWeightLbs,
            loadBasis: p.loadBasis,
            restSeconds: p.restSeconds,
            tempo: p.tempo,
            notes: p.notes,
            deloadModifier: p.deloadModifier,
            validationError: p.validationError ?? null,
          }),
        );
        return {
          key: nextKey("blk"),
          type: block.type,
          title: block.title,
          prescriptions,
        };
      });
      return {
        key: nextKey("day"),
        label: day.label,
        dayOfWeek: day.dayOfWeek,
        locationId: day.locationId,
        locationName: day.locationName,
        blocks,
      };
    });
    return {
      key: nextKey("ph"),
      title: phase.title,
      focus: phase.focus,
      weeks: phase.weeks,
      deloadWeekIndex: phase.deloadWeekIndex,
      days,
      nutritionGuidance: phase.nutritionGuidance ?? null,
    };
  });

  return {
    title: program.title,
    description: program.description,
    goalId: program.goalId,
    startDate: program.startDate,
    source: program.source ?? "AI_GENERATED",
    phases,
    nutritionGuidance: program.nutritionGuidance ?? null,
    issues,
    warnings,
  };
}

// ── Editable draft → CreateProgramRequest (for the commit endpoint) ──
//
// The schedule (training days + per-day gyms) is fixed on the thread, but the
// committed request must still carry a ScheduleInput. We derive it from the
// thread's stored schedule when present, falling back to the edited day tree so
// a gym swap on a day is reflected.

function deriveSchedule(
  draft: ProgramProposalDraft,
  threadSchedule: WorkoutProgramChatSchedule | null,
): ScheduleInput {
  const dayLocations: Partial<Record<WeekDay, string>> = {};
  const trainingDays: WeekDay[] = [];
  for (const phase of draft.phases) {
    for (const day of phase.days) {
      if (!trainingDays.includes(day.dayOfWeek)) trainingDays.push(day.dayOfWeek);
      dayLocations[day.dayOfWeek] = day.locationId;
    }
  }
  if (threadSchedule) {
    // Thread schedule is authoritative for which days are training days, but
    // honor any gym swaps captured in the edited day tree.
    return {
      trainingDays:
        threadSchedule.trainingDays.length > 0
          ? threadSchedule.trainingDays
          : trainingDays,
      dayLocations: { ...threadSchedule.dayLocations, ...dayLocations },
    };
  }
  return { trainingDays, dayLocations };
}

export function draftToCreateRequest(
  draft: ProgramProposalDraft,
  threadSchedule: WorkoutProgramChatSchedule | null,
): CreateProgramRequest {
  const phases: PhaseInput[] = draft.phases.map((phase) => {
    const days: DayInput[] = phase.days.map((day) => {
      const blocks: BlockInput[] = day.blocks.map((block) => {
        const prescriptions: PrescriptionInput[] = block.prescriptions.map(
          (p) => ({
            exerciseId: p.exerciseId,
            sets: p.sets,
            repsMin: p.repsMin,
            repsMax: p.repsMax,
            durationSeconds: p.durationSeconds,
            intensity:
              p.intensity.kind === "NONE" && p.intensity.value == null
                ? null
                : p.intensity,
            targetWeightLbs: p.targetWeightLbs,
            loadBasis: p.loadBasis,
            restSeconds: p.restSeconds,
            tempo: p.tempo && p.tempo.trim() ? p.tempo.trim() : null,
            notes: p.notes && p.notes.trim() ? p.notes.trim() : null,
            deloadModifier: p.deloadModifier,
          }),
        );
        return { type: block.type, title: block.title.trim(), prescriptions };
      });
      return {
        label: day.label.trim(),
        dayOfWeek: day.dayOfWeek,
        locationId: day.locationId,
        blocks,
      };
    });
    return {
      title: phase.title.trim(),
      focus: phase.focus.trim(),
      weeks: phase.weeks,
      deloadWeekIndex: phase.deloadWeekIndex,
      days,
      nutritionGuidance: phase.nutritionGuidance,
    };
  });

  return {
    title: draft.title.trim(),
    description: draft.description.trim(),
    goalId: draft.goalId,
    schedule: deriveSchedule(draft, threadSchedule),
    startDate: draft.startDate,
    source: draft.source ?? "AI_GENERATED",
    phases,
    nutritionGuidance: draft.nutritionGuidance,
  };
}

// ── Factory helpers for the editor (add new nodes) ───────────────────

export function newPrescription(): PrescriptionDraft {
  return {
    key: nextKey("rx"),
    exerciseId: "",
    exercise: null,
    sets: 3,
    repsMin: 8,
    repsMax: 10,
    durationSeconds: null,
    intensity: { kind: "NONE", value: null },
    targetWeightLbs: null,
    loadBasis: null,
    restSeconds: 90,
    tempo: null,
    notes: null,
    deloadModifier: null,
    validationError: null,
  };
}

export function newBlock(type: BlockType = "MAIN"): BlockDraft {
  return { key: nextKey("blk"), type, title: "", prescriptions: [] };
}

export function newDay(
  dayOfWeek: WeekDay,
  locationId: string,
  locationName: string,
): DayDraft {
  return {
    key: nextKey("day"),
    label: "",
    dayOfWeek,
    locationId,
    locationName,
    blocks: [],
  };
}

export function newPhase(): PhaseDraft {
  return {
    key: nextKey("ph"),
    title: "",
    focus: "",
    weeks: 4,
    deloadWeekIndex: null,
    days: [],
    nutritionGuidance: null,
  };
}
