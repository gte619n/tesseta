// Ad-hoc workout library types (IMPL-ADHOC-01). Mirror the backend
// api.adhoc DTOs. The workout body reuses the program tree's WorkoutDay /
// DayResponse types.

import type {
  DayResponse,
  ScheduledStatus,
  WorkoutDay,
  LoggedPrescriptionInput,
} from "./workout-program";

export type AdHocSource = "AI_GENERATED" | "AI_ASSISTED" | "MANUAL";

export type EquipmentContext = {
  presetId: string | null;
  label: string | null;
  equipmentIds: string[];
  freeText: string | null;
};

// Shallow library-list item (no workout body).
export type AdHocWorkoutSummary = {
  adhocId: string;
  title: string;
  summary: string | null;
  source: AdHocSource;
  equipmentContext: EquipmentContext | null;
  targetDurationMinutes: number | null;
  estimatedDurationSeconds: number | null;
  tags: string[];
  pinned: boolean;
  runCount: number;
  lastPerformedAt: string | null;
  createdAt: string | null;
  updatedAt: string | null;
};

// Full template with the resolved workout body.
export type AdHocWorkoutResponse = AdHocWorkoutSummary & {
  prompt: string | null;
  day: DayResponse | null;
};

export type EquipmentPresetId =
  | "hotel-gym"
  | "home"
  | "bodyweight"
  | "full-gym";

export type GenerateAdHocRequest = {
  prompt: string;
  targetDurationMinutes?: number | null;
  presetId?: string | null;
  equipmentIds?: string[] | null;
  freeText?: string | null;
};

export type AdHocViolation = {
  blockId: string;
  orderIndex: number;
  exerciseId: string;
  reason: string;
};

export type AdHocDraft = {
  title: string;
  summary: string | null;
  tags: string[];
  equipmentContext: EquipmentContext | null;
  targetDurationMinutes: number | null;
  estimatedDurationSeconds: number | null;
  day: DayResponse | null;
};

export type GenerateAdHocResponse = {
  draft: AdHocDraft;
  constraintReport: AdHocViolation[];
};

// Body of POST /api/me/adhoc-workouts (persist a template). The `day` is the
// core WorkoutDay shape (ids optional — the server normalizes/mints them).
export type SaveAdHocRequest = {
  title: string;
  summary?: string | null;
  source?: AdHocSource | null;
  prompt?: string | null;
  equipmentContext?: EquipmentContext | null;
  targetDurationMinutes?: number | null;
  tags?: string[];
  pinned?: boolean;
  day: WorkoutDay | DayResponse;
};

export type UpdateAdHocRequest = {
  title?: string | null;
  summary?: string | null;
  tags?: string[] | null;
  pinned?: boolean | null;
  targetDurationMinutes?: number | null;
  day?: WorkoutDay | DayResponse | null;
};

export type LogAdHocRunRequest = {
  status: ScheduledStatus;
  date?: string | null;
  completedAt?: string | null;
  durationSeconds?: number | null;
  logged?: LoggedPrescriptionInput[];
  feeling?: number | null;
};

export type AdHocSort = "recent" | "mostDone" | "created";
