// Wire types for the Exercise catalog (IMPL-14). Import-safe from client and
// server (no apiFetch, no server env). The backend wire format uses uppercase
// Java enum names.

export type MovementPattern =
  | "SQUAT"
  | "HINGE"
  | "LUNGE"
  | "PUSH_HORIZONTAL"
  | "PUSH_VERTICAL"
  | "PULL_HORIZONTAL"
  | "PULL_VERTICAL"
  | "CARRY"
  | "CORE"
  | "CARDIO"
  | "MOBILITY"
  | "STRETCH"
  | "OTHER";

export type Laterality = "BILATERAL" | "UNILATERAL";
export type Mechanic = "COMPOUND" | "ISOLATION";

export type BlockType =
  | "WARMUP"
  | "MOBILITY"
  | "CARDIO"
  | "MAIN"
  | "ACCESSORY"
  | "CORE"
  | "COOLDOWN"
  | "STRETCH";

export type DemoPhase = "START" | "MID" | "END";

export type ExerciseMediaStatus =
  | "NONE"
  | "PENDING"
  | "NEEDS_REVIEW"
  | "APPROVED"
  | "FAILED";

export type ExerciseStatus = "DRAFT" | "PUBLISHED" | "ARCHIVED";

export type EquipmentRequirement = {
  // Each requirement is an any-of group of equipmentIds; a gym satisfies it if
  // it has at least one member. Every group must be satisfied.
  anyOf: string[];
};

// IMPL-19: one planned demo position. The `demoPlan` is an ordered list of
// these — model-derived and admin-reviewed — describing the distinct positions
// an exercise needs to teach. Replaces the fixed START/MID/END triad.
export type FrameSpec = {
  key: string; // stable slug: "start" | "bottom" | "lockout" | "p1"…
  order: number; // 0-based display order
  label: string; // short UI label, e.g. "Bottom"
  caption: string; // one-line teaching cue
  positionPrompt: string; // position clause fed to the image model
};

// IMPL-19: the generated/uploaded image(s) for one FrameSpec, joined by `key`.
// `label`/`caption`/`order` are denormalized from the spec. `phase` is the
// DEPRECATED legacy enum, retained only so pre-plan documents still render.
export type DemoFrame = {
  key: string;
  label: string;
  caption: string;
  order: number;
  imageUrl: string | null;
  imageCandidates: string[];
  phase?: DemoPhase | null; // legacy — pre-IMPL-19 docs
};

// IMPL-19: a public-library match (commit 140eba0). ADMIN-ONLY — the backend
// serializes `reference` as null on user-facing responses (GET /api/exercises,
// /available, /{id}); it is only populated on admin/catalog/review responses.
// Read by the planner and media generator for grounding only — its images are
// never stored or shown to users. Never rely on it in user-facing components.
export type ExerciseReference = {
  url: string;
  source: string; // jefit | rb100 | fedb | yoga
  name: string;
  score: number | null;
  match: string; // name | simplified
  images: string[]; // grounding-only URLs (fedb pairs today)
  groundingImages?: string[] | null; // optional resolved/cached grounding URLs
};

export type RepRange = {
  min: number;
  max: number;
};

export type ExerciseResponse = {
  exerciseId: string;
  name: string;
  aliases: string[];
  movementPattern: MovementPattern;
  primaryMuscles: string[];
  secondaryMuscles: string[];
  laterality: Laterality;
  mechanic: Mechanic;
  description: string;
  formCues: string[];
  requiredEquipment: EquipmentRequirement[];
  suitableBlockTypes: BlockType[];
  defaultRepRange: RepRange | null;
  isTimed: boolean;
  demoFrames: DemoFrame[];
  // IMPL-19: the reviewable frame plan. null ⇒ legacy START/MID/END behavior.
  demoPlan: FrameSpec[] | null;
  planStatus: ExerciseMediaStatus;
  // ADMIN-ONLY. Null on user-facing responses (serialized as null by the
  // backend); only populated on admin/catalog/review responses. Optional here
  // so user-facing code can't lean on it — consume it only in admin components.
  reference?: ExerciseReference | null;
  videoUrl: string | null;
  demoPromptOverride: string | null;
  mediaStatus: ExerciseMediaStatus;
  status: ExerciseStatus;
  contributorId: string | null;
  createdAt: string;
  updatedAt: string;
};

// Editable fields mirror CreateExerciseRequest / UpdateExerciseRequest. For
// PATCH, fields are nullable (omit to leave unchanged).
export type ExerciseEditableFields = {
  name: string;
  aliases: string[];
  movementPattern: MovementPattern;
  primaryMuscles: string[];
  secondaryMuscles: string[];
  laterality: Laterality;
  mechanic: Mechanic;
  description: string;
  formCues: string[];
  requiredEquipment: EquipmentRequirement[];
  suitableBlockTypes: BlockType[];
  defaultRepRange: RepRange | null;
  isTimed: boolean;
  demoPromptOverride: string | null;
};

export type CreateExerciseRequest = ExerciseEditableFields;
export type UpdateExerciseRequest = Partial<ExerciseEditableFields>;

export const MOVEMENT_PATTERNS: MovementPattern[] = [
  "SQUAT",
  "HINGE",
  "LUNGE",
  "PUSH_HORIZONTAL",
  "PUSH_VERTICAL",
  "PULL_HORIZONTAL",
  "PULL_VERTICAL",
  "CARRY",
  "CORE",
  "CARDIO",
  "MOBILITY",
  "STRETCH",
  "OTHER",
];

export const BLOCK_TYPES: BlockType[] = [
  "WARMUP",
  "MOBILITY",
  "CARDIO",
  "MAIN",
  "ACCESSORY",
  "CORE",
  "COOLDOWN",
  "STRETCH",
];

export const DEMO_PHASES: DemoPhase[] = ["START", "MID", "END"];

export const MOVEMENT_PATTERN_LABEL: Record<MovementPattern, string> = {
  SQUAT: "Squat",
  HINGE: "Hinge",
  LUNGE: "Lunge",
  PUSH_HORIZONTAL: "Push (horizontal)",
  PUSH_VERTICAL: "Push (vertical)",
  PULL_HORIZONTAL: "Pull (horizontal)",
  PULL_VERTICAL: "Pull (vertical)",
  CARRY: "Carry",
  CORE: "Core",
  CARDIO: "Cardio",
  MOBILITY: "Mobility",
  STRETCH: "Stretch",
  OTHER: "Other",
};

export const BLOCK_TYPE_LABEL: Record<BlockType, string> = {
  WARMUP: "Warm-up",
  MOBILITY: "Mobility",
  CARDIO: "Cardio",
  MAIN: "Main",
  ACCESSORY: "Accessory",
  CORE: "Core",
  COOLDOWN: "Cool-down",
  STRETCH: "Stretch",
};

export const DEMO_PHASE_LABEL: Record<DemoPhase, string> = {
  START: "Start",
  MID: "Mid",
  END: "End",
};

// IMPL-19: response of GET /api/admin/exercises/{id}/plan.
export type PlanResponse = {
  demoPlan: FrameSpec[];
  planStatus: ExerciseMediaStatus;
};
