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

export const DOMAINS: GoalDomain[] = [
  "CARDIOVASCULAR",
  "BODY_COMPOSITION",
  "STRENGTH",
  "METABOLIC",
  "SLEEP",
  "LONGEVITY",
  "OTHER",
];

export const STEP_KINDS: StepKind[] = [
  "MANUAL",
  "THRESHOLD",
  "SUSTAINED",
  "COUNT",
];

export const STEP_KIND_LABEL: Record<StepKind, string> = {
  MANUAL: "Manual",
  THRESHOLD: "Threshold",
  SUSTAINED: "Sustained",
  COUNT: "Count",
};

export const COMPARATOR_SYMBOL: Record<Comparator, string> = {
  LT: "<",
  LTE: "≤",
  GT: ">",
  GTE: "≥",
  EQ: "=",
};

// The fixed metric-key registry — the contract between Goals and the
// other modules (IMPL-12 spec "Metric key registry"). `lowerIsBetter`
// drives the RangeIndicator good/alert coloring for threshold readouts;
// `unit` is display-only.
export type MetricKeyMeta = {
  key: string;
  label: string;
  unit: string;
  lowerIsBetter: boolean;
};

export const METRIC_REGISTRY: MetricKeyMeta[] = [
  { key: "body.weight", label: "Body weight", unit: "lb", lowerIsBetter: true },
  { key: "body.bodyFatPct", label: "Body fat %", unit: "%", lowerIsBetter: true },
  { key: "body.leanMass", label: "Lean mass", unit: "lb", lowerIsBetter: false },
  { key: "blood.ldl", label: "LDL", unit: "mg/dL", lowerIsBetter: true },
  { key: "blood.apoB", label: "ApoB", unit: "mg/dL", lowerIsBetter: true },
  { key: "blood.hba1c", label: "HbA1c", unit: "%", lowerIsBetter: true },
  { key: "blood.hsCRP", label: "hs-CRP", unit: "mg/L", lowerIsBetter: true },
  { key: "vitals.restingHr", label: "Resting HR", unit: "bpm", lowerIsBetter: true },
  { key: "vitals.hrv", label: "HRV", unit: "ms", lowerIsBetter: false },
  { key: "vitals.sleepScore", label: "Sleep score", unit: "", lowerIsBetter: false },
  { key: "workouts.count", label: "Workouts logged", unit: "", lowerIsBetter: false },
  { key: "workouts.weeklyVolume", label: "Weekly volume", unit: "lb", lowerIsBetter: false },
  { key: "nutrition.proteinAvg7d", label: "Protein (7d avg)", unit: "g", lowerIsBetter: false },
  { key: "nutrition.carbsAvg7d", label: "Carbs (7-day avg)", unit: "g", lowerIsBetter: false },
  { key: "nutrition.fatAvg7d", label: "Fat (7-day avg)", unit: "g", lowerIsBetter: false },
  { key: "nutrition.caloriesAvg7d", label: "Calories (7-day avg)", unit: "kcal", lowerIsBetter: false },
  { key: "meds.adherence30d", label: "Med adherence (30d)", unit: "%", lowerIsBetter: false },
];

export const METRIC_LABEL: Record<string, string> = Object.fromEntries(
  METRIC_REGISTRY.map((m) => [m.key, m.label]),
);

export function metricMeta(key: string): MetricKeyMeta | undefined {
  return METRIC_REGISTRY.find((m) => m.key === key);
}
