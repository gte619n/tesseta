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

export type DemoFrame = {
  phase: DemoPhase;
  imageUrl: string | null;
  imageCandidates: string[];
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
