// Wire types for Workout Programs (IMPL-15). Import-safe from client and
// server. Backend wire format uses uppercase Java enum names.

import type { BlockType, DemoFrame } from "./exercise";

export type WeekDay = "MON" | "TUE" | "WED" | "THU" | "FRI" | "SAT" | "SUN";

export type ProgramStatus = "DRAFT" | "ACTIVE" | "COMPLETED" | "ARCHIVED";
export type ProgramSource = "MANUAL" | "AI_GENERATED" | "AI_ASSISTED";
export type PhaseStatus = "LOCKED" | "ACTIVE" | "COMPLETED";
export type ScheduledStatus = "PLANNED" | "COMPLETED" | "SKIPPED";

export type IntensityKind = "RPE" | "PERCENT_1RM" | "NONE";

export type Intensity = {
  kind: IntensityKind;
  value: number | null;
};

export type DeloadModifier = {
  setsMultiplier: number | null;
  intensityDelta: number | null;
};

// Compact, read-only exercise summary embedded on deep/calendar responses so
// clients render a session without an N+1 fetch per prescription.
export type PrescriptionExercise = {
  exerciseId: string;
  name: string;
  primaryMuscles: string[];
  formCues: string[];
  demoFrames: DemoFrame[];
};

export type Prescription = {
  exerciseId: string;
  orderIndex: number;
  sets: number | null;
  repsMin: number | null;
  repsMax: number | null;
  durationSeconds: number | null;
  intensity: Intensity | null;
  restSeconds: number | null;
  tempo: string | null;
  notes: string | null;
  deloadModifier: DeloadModifier | null;
  exercise: PrescriptionExercise | null;
  // Inline validation note set by the backend validator (e.g. "not executable
  // at this gym"). Used to flag the offending row.
  validationError?: string | null;
};

export type Block = {
  blockId: string;
  type: BlockType;
  title: string;
  orderIndex: number;
  prescriptions: Prescription[];
};

export type WorkoutDay = {
  dayId: string;
  label: string;
  dayOfWeek: WeekDay;
  locationId: string;
  locationName: string;
  orderIndex: number;
  blocks: Block[];
};

export type Phase = {
  phaseId: string;
  title: string;
  focus: string;
  orderIndex: number;
  status: PhaseStatus;
  weeks: number;
  deloadWeekIndex: number | null;
  targetStartDate: string;
  targetEndDate: string;
  days: WorkoutDay[];
};

// Shallow list response.
export type WorkoutProgramResponse = {
  programId: string;
  title: string;
  description: string;
  goalId: string | null;
  status: ProgramStatus;
  source: ProgramSource;
  startDate: string;
  trainingDays: WeekDay[];
  totalWeeks: number;
  phaseCount: number;
  completedPhaseCount: number;
  createdAt: string;
  updatedAt: string;
};

// Deep response with phases → days → blocks → prescriptions.
export type WorkoutProgramDeepResponse = {
  programId: string;
  title: string;
  description: string;
  goalId: string | null;
  goalTitle: string | null;
  status: ProgramStatus;
  source: ProgramSource;
  startDate: string;
  trainingDays: WeekDay[];
  phases: Phase[];
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
};

// A DayResponse: same shape as a WorkoutDay's session, used by ScheduledWorkout.
export type DayResponse = WorkoutDay;

export type ScheduledWorkoutResponse = {
  scheduledId: string;
  date: string;
  phaseId: string;
  dayId: string;
  dayLabel: string;
  weekIndexInPhase: number;
  isDeload: boolean;
  locationId: string;
  locationName: string;
  status: ScheduledStatus;
  session: DayResponse;
};

// ── Create / update request shapes (mirror backend records) ──────────

export type PrescriptionInput = {
  exerciseId: string;
  sets: number | null;
  repsMin: number | null;
  repsMax: number | null;
  durationSeconds: number | null;
  intensity: Intensity | null;
  restSeconds: number | null;
  tempo: string | null;
  notes: string | null;
  deloadModifier: DeloadModifier | null;
};

export type BlockInput = {
  type: BlockType;
  title: string;
  prescriptions: PrescriptionInput[];
};

export type DayInput = {
  label: string;
  dayOfWeek: WeekDay;
  locationId: string;
  blocks: BlockInput[];
};

export type PhaseInput = {
  title: string;
  focus: string;
  weeks: number;
  deloadWeekIndex: number | null;
  days: DayInput[];
};

export type ScheduleInput = {
  trainingDays: WeekDay[];
  dayLocations: Partial<Record<WeekDay, string>>;
};

export type CreateProgramRequest = {
  title: string;
  description: string;
  goalId: string | null;
  schedule: ScheduleInput;
  startDate: string;
  source: ProgramSource;
  phases: PhaseInput[];
};

export type UpdateProgramRequest = Partial<{
  title: string;
  description: string;
  goalId: string | null;
  schedule: ScheduleInput;
  startDate: string;
  status: ProgramStatus;
}>;

export const WEEK_DAYS: WeekDay[] = [
  "MON",
  "TUE",
  "WED",
  "THU",
  "FRI",
  "SAT",
  "SUN",
];

export const WEEK_DAY_LABEL: Record<WeekDay, string> = {
  MON: "Mon",
  TUE: "Tue",
  WED: "Wed",
  THU: "Thu",
  FRI: "Fri",
  SAT: "Sat",
  SUN: "Sun",
};
