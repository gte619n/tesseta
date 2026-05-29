// Mirrors backend DTOs from backend/api/.../api/goals/dto/*.java
// Keep in sync when the backend record shapes change.

export type GoalDomain =
  | "CARDIOVASCULAR"
  | "BODY_COMPOSITION"
  | "STRENGTH"
  | "METABOLIC"
  | "SLEEP"
  | "LONGEVITY"
  | "OTHER";

export type GoalStatus = "ACTIVE" | "COMPLETED" | "ARCHIVED";

export type GoalSource = "MANUAL" | "AI_GENERATED" | "AI_ASSISTED";

export type PhaseStatus = "LOCKED" | "ACTIVE" | "COMPLETED";

export type StepKind = "MANUAL" | "THRESHOLD" | "SUSTAINED" | "COUNT";

export type Comparator = "LT" | "LTE" | "GT" | "GTE" | "EQ";

export type StepMetricBinding = {
  metricKey: string;
  comparator: Comparator;
  targetValue: number;
  windowDays: number | null;
  countFrom: string | null;
};

export type GoalResponse = {
  goalId: string;
  title: string;
  description: string;
  domain: GoalDomain;
  status: GoalStatus;
  startDate: string;
  targetDate: string;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
  phaseOrder: string[];
  source: GoalSource;
};

export type StepResponse = {
  stepId: string;
  phaseId: string;
  goalId: string;
  title: string;
  orderIndex: number;
  kind: StepKind;
  done: boolean;
  doneAt: string | null;
  manualOverride: boolean;
  metric: StepMetricBinding | null;
  metricRegressed: boolean | null;
};

export type PhaseResponse = {
  phaseId: string;
  goalId: string;
  title: string;
  description: string;
  orderIndex: number;
  status: PhaseStatus;
  targetStartDate: string;
  targetEndDate: string;
  completedAt: string | null;
  stepOrder: string[];
  steps: StepResponse[];
};

export type GoalDeepResponse = GoalResponse & {
  phases: PhaseResponse[];
};

export const DOMAIN_LABEL: Record<GoalDomain, string> = {
  CARDIOVASCULAR: "Cardiovascular",
  BODY_COMPOSITION: "Body composition",
  STRENGTH: "Strength",
  METABOLIC: "Metabolic",
  SLEEP: "Sleep",
  LONGEVITY: "Longevity",
  OTHER: "Other",
};
