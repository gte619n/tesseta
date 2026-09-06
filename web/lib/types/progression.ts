// Wire types for the progression console (IMPL-PROG-01). These match the
// backend JSON contract 1:1 (camelCase, same field names as the Android DTOs):
//   GET api/me/progression/week-review        → PatternReview[]
//   GET api/me/progression/block-parameters   → BlockParameters
//   PUT api/me/progression/block-parameters   → BlockParameters
//   GET api/me/progression/strength           → ExerciseStrength[]

// Per-exercise estimated 1RM — the engine's authoritative "weight" belief for a
// lift, named and confidence-graded. Sorted heaviest-first by the backend.
export interface ExerciseStrength {
  exerciseId: string;
  name: string;
  movementPattern: string | null;
  e1rmLbs: number;
  /** "LOW" | "MEDIUM" | "HIGH" — from the belief's relative uncertainty. */
  confidence: string;
  observationCount: number;
}

// The backend's weekly analysis for one movement pattern (e.g. PUSH_HORIZONTAL):
// the volume trend, a fatigue read, and whether it proposes moving the
// working-set target up/down (or deloading) for the coming week. Read-only.
export interface PatternReview {
  /** Movement-pattern enum name, e.g. "PUSH_HORIZONTAL". */
  pattern: string;
  /** "RISING" | "FLAT" | "FALLING" | "UNKNOWN". */
  trend: string;
  weeklySlopePct: number;
  fatigueIndex: number;
  currentTarget: number;
  proposedTarget: number;
  deload: boolean;
  reasoning: string;
}

// The active training block's parameters: the mode driving progression, the
// per-pattern rep ranges + weekly-set ceilings, and the RIR caps by exercise
// class. `manualOverride` is true once the user pins the mode by hand.
export interface BlockParameters {
  /** "GAINING" | "RECOMP" | "MAINTENANCE" | "RECOVERY". */
  mode: string;
  expectedDriftPerDay: number;
  /** "ADD_LOAD" | "HOLD_LOAD_AT_LOWER_RIR". */
  successCriterion: string;
  manualOverride: boolean;
  /** Pattern enum name → [min, max] reps (2-element list on the wire). */
  repRanges: Record<string, number[]>;
  /** "COMPOUND" | "ISOLATION" → RIR cap. */
  rirCaps: Record<string, number>;
  /** Pattern enum name → weekly working-set ceiling. */
  weeklyCeiling: Record<string, number>;
}

// Partial update of the block parameters. Every field is optional; an omitted
// field is left unchanged by the backend.
export interface UpdateBlockParametersRequest {
  mode?: string;
  successCriterion?: string;
  expectedDriftPerDay?: number;
}
