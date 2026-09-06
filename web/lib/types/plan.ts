import type { Macros } from "./nutrition";

// The plan-coherence view (IMPL-PLAN-01): one surface that pulls the whole
// chain together — Goal → active Program/phase → calorie target → today's
// intake → the progression engine's measured energy state — and surfaces where
// they diverge. All pieces are optional; the view degrades to what's present.

// The engine's measured energy state (backend GET /api/me/progression/
// energy-balance). `mode` is what the measured balance *implies*; the pinned/
// effective block mode may differ (see PlanChain.block).
export type EnergyBalance = {
  maintenanceKcal: number;
  meanIntakeKcal: number;
  balanceKcal: number;
  mode: string;
  // False until enough logged intake accrues; maintenance is then a cold-start
  // estimate and the balance is not meaningful yet.
  hasIntakeData: boolean;
};

// A single program phase's nutrition guidance, flattened for the view.
export type PhaseGuidance = {
  kcal: number | null;
  proteinG: number | null;
  carbsG: number | null;
  fatG: number | null;
};

// The assembled chain the presentational component renders. Built by
// lib/plan-api.ts; also the shape the sample-data preview supplies.
export type PlanChain = {
  goal: { id: string; title: string; domain: string } | null;
  program: {
    id: string;
    title: string;
    goalId: string | null;
    phaseTitle: string | null;
    guidance: PhaseGuidance | null;
  } | null;
  target: Macros | null;
  today: { date: string; totals: Macros } | null;
  energy: EnergyBalance | null;
  block: { mode: string; manualOverride: boolean } | null;
};

export type PlanFlagSeverity = "action" | "warn" | "info";

// What a reconcile button does. The page wires each to a server action; the
// preview no-ops them.
export type PlanActionKind =
  | "applyProgramNutrition"
  | "setTargetFromMaintenance"
  | "linkProgramToGoal";

export type PlanFlag = {
  id: string;
  severity: PlanFlagSeverity;
  // Tabler icon name (rendered as `ti ti-<icon>`), chosen per flag meaning.
  icon: string;
  title: string;
  detail: string;
  // A short "what to do / why it matters" line under the detail.
  hint?: string;
  action?: { label: string; kind: PlanActionKind };
};
