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

// One set as actually performed. Populated on completed-session snapshots
// (history import + the IMPL-17 logger); null/absent on plan templates. Reps
// are null in the imported export — only the weight was recorded. RPE, rest,
// and the per-set timestamp are full actuals from live logging (ADR-0012
// Decision 2) — always nullable, never required.
export type LoggedSet = {
  weightLbs: number | null;
  reps: number | null;
  rpe: number | null;
  restSeconds: number | null;
  completedAt: string | null;
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
  loggedSets: LoggedSet[] | null;
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

// Lightweight history counts for the Workouts hub (no full session payloads).
export type WorkoutHistorySummary = {
  count: number;
  lastWorkoutDate: string | null;
};

export type ScheduledWorkoutResponse = {
  scheduledId: string;
  date: string;
  // Owning program. `scheduledId` is only unique within a program (IMPL-17 D1),
  // so cross-program reads (Workout History) need this to address the
  // completion upsert. Optional: program-scoped responses may omit it.
  programId?: string | null;
  phaseId: string;
  dayId: string;
  dayLabel: string;
  weekIndexInPhase: number;
  isDeload: boolean;
  locationId: string;
  locationName: string;
  status: ScheduledStatus;
  session: DayResponse;
  // Performed-session metadata (history import). Null for PLANNED sessions.
  completedAt: string | null;
  durationSeconds: number | null;
};

// ── Session completion (IMPL-17 D2) ──────────────────────────────────
//
// PUT /api/me/workout-programs/{programId}/sessions/{scheduledId} — the
// idempotent "log result / edit actuals" upsert from ADR-0012. Prescriptions
// have no id, so logged sets key by (blockId, orderIndex) against the session
// snapshot; unknown keys are a 400. Repeat PUTs replace actuals and re-run the
// backend fan-out.

export type LoggedSetInput = {
  weightLbs: number | null;
  reps: number | null;
  rpe: number | null;
  restSeconds: number | null;
  completedAt: string | null;
};

export type LoggedPrescriptionInput = {
  blockId: string;
  orderIndex: number;
  sets: LoggedSetInput[];
};

export type CompleteSessionRequest = {
  status: "COMPLETED" | "SKIPPED";
  completedAt: string | null; // ISO instant; required when COMPLETED
  durationSeconds: number | null; // required when COMPLETED
  logged: LoggedPrescriptionInput[]; // SKIPPED clears actuals (send [])
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

// ── Persistent chat threads (mirror backend ChatThreadResponse) ──────

// The schedule chosen in the pre-chat setup form. Fixed for the lifetime of a
// thread; the per-gym exercise allow-lists derive from these gyms.
export type WorkoutProgramChatSchedule = {
  trainingDays: WeekDay[];
  dayLocations: Partial<Record<WeekDay, string>>;
};

// GET /api/me/workout-programs/chat/threads element.
export type WorkoutProgramChatThread = {
  threadId: string;
  title: string;
  schedule: WorkoutProgramChatSchedule | null;
  goalId: string | null;
  createdAt: string;
  updatedAt: string;
};

export type WorkoutProgramChatMessageRole = "USER" | "ASSISTANT";

// GET /api/me/workout-programs/chat/{threadId} element. `proposalJson` is a
// JSON string of { program: WorkoutProgramDeepResponse, issues: string[] } on
// assistant turns that produced a proposal; null otherwise.
export type WorkoutProgramChatMessage = {
  messageId: string;
  role: WorkoutProgramChatMessageRole;
  content: string;
  proposalJson: string | null;
  createdAt: string;
};

// Parsed shape of an assistant message's `proposalJson` and the SSE `proposal`
// event payload.
export type WorkoutProgramProposalPayload = {
  program: WorkoutProgramDeepResponse;
  issues: string[];
};

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
